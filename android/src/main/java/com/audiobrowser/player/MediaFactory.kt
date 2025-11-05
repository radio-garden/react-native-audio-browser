package com.audiobrowser.player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory

class MediaFactory(private val context: Context, private val cache: SimpleCache?) :
  MediaSource.Factory {

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
    val uri = mediaItem.localConfiguration?.uri
    val factory: DataSource.Factory = when {
      uri != null && isLocalFile(uri)-> { DefaultDataSource.Factory(context) }
      else -> {
        val userAgent = mediaItem.mediaMetadata.extras?.getString("user-agent") ?: DEFAULT_USER_AGENT
        val headers =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mediaItem.mediaMetadata.extras?.getSerializable("headers", HashMap::class.java)
          } else {
            mediaItem.mediaMetadata.extras?.getSerializable("headers")
          }

        DefaultHttpDataSource.Factory().apply {
          setUserAgent(userAgent)
          setAllowCrossProtocolRedirects(true)
          headers?.let { setDefaultRequestProperties(it as HashMap<String, String>) }
        }
      }
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
      .createMediaSource(mediaItem)
  }
}
