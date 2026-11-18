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
 * Resolves a track's media URL through its request config, which may call into JS, in [open] on
 * ExoPlayer's IO thread. The first open caches the result on the [Factory]; opens of other URLs
 * (HLS segments, keys) keep their own URL and reuse the cached headers and user agent.
 */
class TransformingDataSource(private val upstream: DataSource, private val factory: Factory) :
  DataSource {

  companion object {
    // MediaFactory's default request property: a config with this user agent adds no header.
    private const val DEFAULT_USER_AGENT = "react-native-audio-browser"

    private const val OPEN_ENDED_RANGE = "bytes=0-"

    // A progressive read from the start sends `Range: bytes=0-`, as a browser does: some live
    // servers cut a browser User-Agent off after a few seconds without it. Media3 marks every
    // progressive read with `Icy-MetaData`; HLS reads go without.
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
        // A reopen of the media URL reuses the resolved URL, as the original may be a schemeless
        // path DefaultDataSource reads as a file. After a failed open it re-resolves once, for an
        // expired URL or token.
        originalUrl == factory.cachedOriginalUrl ->
          if (factory.reresolveOnNextMediaOpen) {
            factory.reresolveOnNextMediaOpen = false
            resolveAndCache(originalUrl)
          } else {
            cached
          }
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
      // A failed segment or key leaves the media URL alone.
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

  // Media3 reads `icy-metaint` from these to strip ICY metadata from the stream; without it the
  // metadata is lost and its bytes play as audio.
  override fun getResponseHeaders(): Map<String, List<String>> {
    return upstream.responseHeaders
  }

  override fun close() {
    upstream.close()
    resolvedUri = null
  }

  // Blocks on JS: never call it on the main thread. Without a config, or when the callback throws,
  // the original URL with no headers and the default user agent.
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

    // One Factory per track, so the cache resets on track change.
    @Volatile internal var cachedTransform: Triple<String, Map<String, String>, String>? = null

    @Volatile internal var cachedOriginalUrl: String? = null

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
