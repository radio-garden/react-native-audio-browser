package com.audiobrowser.player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import timber.log.Timber

class MediaFactory(
  private val context: Context,
  private val cache: SimpleCache?,
  private val getRequestConfig: (originalUrl: String) -> MediaRequestConfig?,
) : MediaSource.Factory {

  companion object {
    private const val DEFAULT_USER_AGENT = "react-native-audio-browser"
  }

  private val mediaFactory = DefaultMediaSourceFactory(context)

  override fun setDrmSessionManagerProvider(
    drmSessionManagerProvider: DrmSessionManagerProvider
  ): MediaSource.Factory {
    return mediaFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
  }

  override fun setLoadErrorHandlingPolicy(
    loadErrorHandlingPolicy: LoadErrorHandlingPolicy
  ): MediaSource.Factory {
    return mediaFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
  }

  override fun getSupportedTypes(): IntArray {
    return mediaFactory.supportedTypes
  }

  /**
   * Get the final request configuration for a media URL. Returns the URL, headers, and user-agent
   * to use for streaming.
   */
  private fun getMediaRequestConfig(
    originalUrl: String
  ): Triple<String, Map<String, String>, String> {
    return try {
      val requestConfig = getRequestConfig(originalUrl)
      Timber.d("Got media request config for URL '$originalUrl': path=${requestConfig?.path}, baseUrl=${requestConfig?.baseUrl}, headers=${requestConfig?.headers}, userAgent=${requestConfig?.userAgent}")
      if (requestConfig != null) {
        val path = requestConfig.path ?: ""
        val baseUrl = requestConfig.baseUrl

        // Build URL using the helper to ensure proper slash handling
        val url = com.audiobrowser.util.BrowserPathHelper.buildUrl(baseUrl, path)

        // Add query parameters if any
        val finalUrl =
          if (requestConfig.query?.isNotEmpty() == true) {
            val uri = Uri.parse(url).buildUpon()
            requestConfig.query.forEach { (key, value) -> uri.appendQueryParameter(key, value) }
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
      Timber.e(e, "Error getting media request config for URL: $originalUrl")
      Triple(originalUrl, emptyMap(), DEFAULT_USER_AGENT)
    }
  }

  private fun isLocalFile(uri: Uri): Boolean {
    val scheme = uri.scheme
    return when (scheme) {
      null,
      ContentResolver.SCHEME_FILE,
      ContentResolver.SCHEME_ANDROID_RESOURCE,
      ContentResolver.SCHEME_CONTENT,
      "res" -> true
      else -> false
    }
  }

  override fun createMediaSource(mediaItem: MediaItem): MediaSource {
    val originalUri = mediaItem.localConfiguration?.uri
    val (finalUrl, headers, userAgent) = getMediaRequestConfig(originalUri!!.toString())

    val local = isLocalFile(finalUrl.toUri())
    val factory: DataSource.Factory =
      when {
        local -> {
          DefaultDataSource.Factory(context)
        }
        finalUrl != null -> {

          Timber.d("Media URL: $originalUri -> $finalUrl")
          Timber.d("Media headers: $headers")
          Timber.d("Media user-agent: $userAgent")

          DefaultHttpDataSource.Factory().apply {
            setUserAgent(userAgent)
            setAllowCrossProtocolRedirects(true)
            if (headers.isNotEmpty()) {
              setDefaultRequestProperties(headers)
            }
          }
        }
        else -> {
          DefaultDataSource.Factory(context)
        }
      }

    // Create a new MediaItem with the transformed URL if it changed
    val finalMediaItem =
      if (finalUrl != originalUri.toString()) {
        Timber.d("Creating new MediaItem with transformed URL: $finalUrl")
        mediaItem.buildUpon().setUri(finalUrl.toUri()).build()
      } else {
        mediaItem
      }

    return ProgressiveMediaSource.Factory(
        cache?.let {
          CacheDataSource.Factory()
            .setCache(it)
            .setUpstreamDataSourceFactory(factory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } ?: factory,
        DefaultExtractorsFactory()
          // TODO: reconsider whether this should be enabled by default:
          .setConstantBitrateSeekingEnabled(true),
      )
      .createMediaSource(finalMediaItem)
  }
}
