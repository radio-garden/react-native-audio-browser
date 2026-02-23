package com.audiobrowser.player

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.audiobrowser.util.BrowserPathHelper
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import timber.log.Timber

/**
 * A DataSource wrapper that defers media URL transformation to ExoPlayer's IO thread.
 *
 * ExoPlayer calls [MediaSource.Factory.createMediaSource] on the main thread during
 * `addMediaItem()`, but calls [DataSource.open] on a background IO thread when it
 * needs to fetch data. By deferring URL transformation (which may invoke a JS callback
 * via `runBlocking`) to [open], we avoid blocking the main thread and prevent deadlocks
 * when the JS thread is occupied by synchronous Nitro calls (e.g., `seekTo`).
 */
class TransformingDataSource(
  private val upstream: DataSource,
  private val getRequestConfig: (originalUrl: String) -> MediaRequestConfig?,
) : DataSource {

  companion object {
    private const val DEFAULT_USER_AGENT = "react-native-audio-browser"
  }

  private var resolvedUri: Uri? = null

  override fun open(dataSpec: DataSpec): Long {
    val originalUrl = dataSpec.uri.toString()

    // Resolve the transform on the IO thread — safe to block here since
    // the main thread and JS thread remain free for other work.
    val (finalUrl, headers, userAgent) = resolveRequestConfig(originalUrl)

    Timber.d("TransformingDataSource: $originalUrl -> $finalUrl")

    // Build merged headers including user-agent override
    val mergedHeaders = buildMap {
      putAll(dataSpec.httpRequestHeaders)
      putAll(headers)
      if (userAgent != DEFAULT_USER_AGENT) {
        put("User-Agent", userAgent)
      }
    }

    // Build a new DataSpec with the transformed URL and merged headers
    val transformedSpec =
      dataSpec.buildUpon().setUri(finalUrl.toUri()).setHttpRequestHeaders(mergedHeaders).build()

    resolvedUri = transformedSpec.uri
    return upstream.open(transformedSpec)
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

  override fun close() {
    upstream.close()
    resolvedUri = null
  }

  /**
   * Resolves the media request configuration for the given URL. This calls the JS transform
   * callback if configured. Safe to call from a background thread.
   *
   * @return Triple of (finalUrl, headers, userAgent)
   */
  private fun resolveRequestConfig(
    originalUrl: String
  ): Triple<String, Map<String, String>, String> {
    return try {
      val requestConfig = getRequestConfig(originalUrl)
      if (requestConfig != null) {
        val path = requestConfig.path ?: ""
        val baseUrl = requestConfig.baseUrl
        val url = BrowserPathHelper.buildUrl(baseUrl, path)

        val finalUrl =
          if (requestConfig.query?.isNotEmpty() == true) {
            val uri = Uri.parse(url).buildUpon()
            requestConfig.query!!.forEach { (key, value) -> uri.appendQueryParameter(key, value) }
            uri.build().toString()
          } else {
            url
          }

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

  /** Factory that creates [TransformingDataSource] instances wrapping an upstream factory. */
  class Factory(
    private val upstreamFactory: DataSource.Factory,
    private val getRequestConfig: (originalUrl: String) -> MediaRequestConfig?,
  ) : DataSource.Factory {
    override fun createDataSource(): DataSource {
      return TransformingDataSource(
        upstream = upstreamFactory.createDataSource(),
        getRequestConfig = getRequestConfig,
      )
    }
  }
}
