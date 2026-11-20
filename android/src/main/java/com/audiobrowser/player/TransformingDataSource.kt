package com.audiobrowser.player

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.audiobrowser.util.BrowserPathHelper
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import java.io.IOException
import timber.log.Timber

/**
 * Wraps an upstream [DataSource] and resolves each track's media URL through its request config
 * (which may call into JS) in [open], on ExoPlayer's IO thread. Resolving on the main thread, in
 * `createMediaSource`, deadlocks while a synchronous Nitro call such as `seekTo` holds the JS
 * thread.
 *
 * The first open resolves the media URL and caches the result on the [Factory]. A later open of the
 * media URL (a seek, a playlist refresh) reuses it, unless the track reloaded or the last open or
 * read of it failed: the URL or its token may have expired. Opens of other URLs (HLS segments,
 * keys) keep their own URL and reuse the cached headers and user agent.
 */
class TransformingDataSource(private val upstream: DataSource, private val factory: Factory) :
  DataSource {

  companion object {
    // Matches the default request property MediaFactory sets upstream; a config with this user
    // agent adds no User-Agent header of its own.
    private const val DEFAULT_USER_AGENT = "react-native-audio-browser"

    private const val OPEN_ENDED_RANGE = "bytes=0-"

    /**
     * Whether to send `Range: bytes=0-` on a progressive read from the start, where media3 sends
     * none, as a browser does: some live servers cut a browser User-Agent without it off after a
     * few seconds. Media3 marks every progressive read with `Icy-MetaData`; HLS reads go without.
     */
    private fun wantsOpenEndedRange(dataSpec: DataSpec, headers: Map<String, String>): Boolean =
      dataSpec.position == 0L &&
        dataSpec.length == C.LENGTH_UNSET.toLong() &&
        headers.keys.any { it.equals("Icy-MetaData", ignoreCase = true) } &&
        headers.keys.none { it.equals("Range", ignoreCase = true) }
  }

  private var resolvedUri: Uri? = null

  private var openedMediaUrl = false

  override fun open(dataSpec: DataSpec): Long {
    val originalUrl = dataSpec.uri.toString()

    val cached = factory.cachedTransform
    val (finalUrl, headers, userAgent) =
      when {
        cached == null -> resolveAndCache(originalUrl)
        // The resolved URL, never the original: that may be schemeless ("/listen/<id>/x.mp3"),
        // which DefaultDataSource routes to a file and fails.
        originalUrl == factory.cachedOriginalUrl ->
          if (factory.resolveOnNextMediaOpen) resolveAndCache(originalUrl) else cached
        // An already-absolute segment or key URL: keep it, reuse the headers.
        else -> Triple(originalUrl, cached.second, cached.third)
      }

    val requestHeaders = buildMap {
      putAll(dataSpec.httpRequestHeaders)
      putAll(headers)
      val headerAgent =
        keys.filter { it.equals("User-Agent", ignoreCase = true) }.map { remove(it) }.lastOrNull()
      // Under this exact name, so it replaces MediaFactory's default rather than sitting beside it.
      val agent = if (userAgent != DEFAULT_USER_AGENT) userAgent else headerAgent
      agent?.let { put("User-Agent", it) }
    }
    val mergedHeaders =
      if (wantsOpenEndedRange(dataSpec, requestHeaders)) {
        requestHeaders + ("Range" to OPEN_ENDED_RANGE)
      } else {
        requestHeaders
      }

    val transformedSpec =
      dataSpec.buildUpon().setUri(finalUrl.toUri()).setHttpRequestHeaders(mergedHeaders).build()

    resolvedUri = transformedSpec.uri
    openedMediaUrl = originalUrl == factory.cachedOriginalUrl
    return try {
      upstream.open(transformedSpec)
    } catch (e: IOException) {
      // Only a media-URL failure refreshes the URL; a failed segment or key leaves it alone.
      if (openedMediaUrl) {
        factory.resolveOnNextMediaOpen = true
      }
      throw e
    }
  }

  private fun resolveAndCache(originalUrl: String): Triple<String, Map<String, String>, String> {
    factory.resolveOnNextMediaOpen = false
    val resolved = resolveRequestConfig(originalUrl)
    factory.cachedTransform = resolved
    factory.cachedOriginalUrl = originalUrl
    Timber.d("TransformingDataSource: resolved $originalUrl -> ${resolved.first}")
    return resolved
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    return try {
      upstream.read(buffer, offset, length)
    } catch (e: IOException) {
      // An in-place retry reopens the media URL after a dropped read. A load cancelled for a seek,
      // whose thread the loader interrupts, keeps it.
      if (openedMediaUrl && !Thread.currentThread().isInterrupted) {
        factory.resolveOnNextMediaOpen = true
      }
      throw e
    }
  }

  override fun addTransferListener(transferListener: TransferListener) {
    upstream.addTransferListener(transferListener)
  }

  override fun getUri(): Uri? {
    return resolvedUri ?: upstream.uri
  }

  /**
   * Upstream's response headers. Media3's `ProgressiveMediaPeriod` reads `icy-metaint` from them to
   * install `IcyDataSource`; the `DataSource` default of an empty map would lose ICY song metadata
   * (`onMetadata` never fires) and leave the metadata bytes in the stream, decoded as audible
   * chirps.
   */
  override fun getResponseHeaders(): Map<String, List<String>> {
    return upstream.responseHeaders
  }

  override fun close() {
    upstream.close()
    resolvedUri = null
    openedMediaUrl = false
  }

  /**
   * Runs the request config callback for [originalUrl], blocking on JS if needed; call only off the
   * main thread. Returns (finalUrl, headers, userAgent); without a config, or when the callback
   * throws, the original URL with no headers and the default user agent.
   */
  private fun resolveRequestConfig(
    originalUrl: String
  ): Triple<String, Map<String, String>, String> {
    return try {
      val requestConfig = factory.getRequestConfig(originalUrl)
      if (requestConfig != null) {
        val finalUrl = requestConfig.url()
        val headers = requestConfig.headers ?: emptyMap()
        val userAgent = requestConfig.userAgent ?: DEFAULT_USER_AGENT

        Triple(finalUrl, headers, userAgent)
      } else {
        Triple(originalUrl, emptyMap(), DEFAULT_USER_AGENT)
      }
    } catch (e: Exception) {
      Timber.e(e, "Error resolving media request config for URL: $originalUrl")
      Triple(originalUrl, emptyMap(), DEFAULT_USER_AGENT)
    }
  }

  class Factory(
    private val upstreamFactory: DataSource.Factory,
    internal val getRequestConfig: (originalUrl: String) -> MediaRequestConfig?,
  ) : DataSource.Factory {

    // One Factory per track (MediaFactory.createMediaSource), so the cache resets on track change.
    @Volatile internal var cachedTransform: Triple<String, Map<String, String>, String>? = null

    @Volatile internal var cachedOriginalUrl: String? = null

    /**
     * Set by a failed open or read of the media URL and by [ResolvingOnReloadSource] on a reload;
     * the next open of the media URL resolves it again and clears it.
     */
    @Volatile internal var resolveOnNextMediaOpen: Boolean = false

    override fun createDataSource(): DataSource {
      return TransformingDataSource(upstream = upstreamFactory.createDataSource(), factory = this)
    }
  }
}

/** The URL this config requests: its path against its base URL, with its query appended. */
internal fun MediaRequestConfig.url(): String {
  val url = BrowserPathHelper.buildUrl(baseUrl, path ?: "")
  val query = query?.takeIf { it.isNotEmpty() } ?: return url
  val uri = Uri.parse(url).buildUpon()
  query.forEach { (key, value) -> uri.appendQueryParameter(key, value) }
  return uri.build().toString()
}
