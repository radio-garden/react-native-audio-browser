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
 * The first open resolves the media URL and caches the result on the [Factory]; later opens of
 * other URLs (HLS segments, keys) keep their own URL and reuse the cached headers and user agent.
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

  override fun open(dataSpec: DataSpec): Long {
    val originalUrl = dataSpec.uri.toString()

    val cached = factory.cachedTransform
    val (finalUrl, headers, userAgent) =
      when {
        cached == null -> resolveAndCache(originalUrl)
        // Re-open of the media URL (a retry) reuses the resolved URL: the original may be
        // schemeless ("/listen/<id>/x.mp3"), which DefaultDataSource routes to a file and fails.
        // After a failed load it re-resolves once instead, since the URL or token may have expired.
        originalUrl == factory.cachedOriginalUrl ->
          if (factory.reresolveOnNextMediaOpen) {
            factory.reresolveOnNextMediaOpen = false
            resolveAndCache(originalUrl)
          } else {
            cached
          }
        // An already-absolute segment or key URL: keep it, reuse the headers.
        else -> Triple(originalUrl, cached.second, cached.third)
      }

    val requestHeaders = buildMap {
      putAll(dataSpec.httpRequestHeaders)
      putAll(headers)
      if (userAgent != DEFAULT_USER_AGENT) {
        put("User-Agent", userAgent)
      }
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
    return try {
      upstream.open(transformedSpec)
    } catch (e: IOException) {
      // Only a media-URL failure refreshes the URL; a failed segment or key leaves it alone.
      if (originalUrl == factory.cachedOriginalUrl) {
        factory.reresolveOnNextMediaOpen = true
      }
      throw e
    }
  }

  private fun resolveAndCache(originalUrl: String): Triple<String, Map<String, String>, String> {
    val resolved = resolveRequestConfig(originalUrl)
    factory.cachedTransform = resolved
    factory.cachedOriginalUrl = originalUrl
    Timber.d("TransformingDataSource: resolved $originalUrl -> ${resolved.first}")
    return resolved
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    return upstream.read(buffer, offset, length)
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

    // Set by a failed media-URL open; the next media-URL open re-resolves and clears it.
    @Volatile internal var reresolveOnNextMediaOpen: Boolean = false

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
