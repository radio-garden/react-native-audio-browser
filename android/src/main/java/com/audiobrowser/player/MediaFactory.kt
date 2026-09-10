package com.audiobrowser.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import com.audiobrowser.model.RetryPolicy
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class MediaFactory(
  private val context: Context,
  private val cache: SimpleCache?,
  private val retryPolicy: RetryPolicy,
  private val shouldRetry: () -> Boolean,
  private val isOnline: () -> Boolean = { true },
  private val hasPlayed: () -> Boolean = { false },
  private val onRetryPending: ((exception: IOException, isNetworkError: Boolean) -> Unit)? = null,
  private val transferListener: TransferListener? = null,
  private val getRequestConfig: (originalUrl: String) -> MediaRequestConfig?,
) : MediaSource.Factory {

  companion object {
    private const val DEFAULT_USER_AGENT = "react-native-audio-browser"

    /**
     * The client media requests go out on. One per process so connections are reused; not
     * `HttpClient`'s browse client, whose read timeout is sized for JSON and would cut a long media
     * read short. The timeouts are media3's own defaults, so stall detection and
     * [RetryLoadErrorHandlingPolicy] behave as they did before.
     */
    private val mediaHttpClient: OkHttpClient by lazy {
      OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    }
  }

  private val extractorsFactory =
    DefaultExtractorsFactory()
      // TODO: reconsider whether this should be enabled by default:
      .setConstantBitrateSeekingEnabled(true)

  // Store reference to custom retry policy so we can reset it on track changes
  private val customRetryPolicy: RetryLoadErrorHandlingPolicy? =
    when (retryPolicy) {
      is RetryPolicy.Default -> null
      is RetryPolicy.Infinite ->
        RetryLoadErrorHandlingPolicy(
          maxRetries = null,
          maxRetryDurationMs = retryPolicy.maxRetryDurationMs,
          firstConnectMaxRetryDurationMs = retryPolicy.firstConnectMaxRetryDurationMs,
          shouldRetry = shouldRetry,
          isOnline = isOnline,
          hasPlayed = hasPlayed,
          onRetryPending = onRetryPending,
        )
      is RetryPolicy.Limited ->
        RetryLoadErrorHandlingPolicy(
          maxRetries = retryPolicy.maxRetries,
          maxRetryDurationMs = retryPolicy.maxRetryDurationMs,
          firstConnectMaxRetryDurationMs = retryPolicy.firstConnectMaxRetryDurationMs,
          shouldRetry = shouldRetry,
          isOnline = isOnline,
          hasPlayed = hasPlayed,
          onRetryPending = onRetryPending,
        )
    }

  private val mediaFactory =
    DefaultMediaSourceFactory(context, extractorsFactory).apply {
      customRetryPolicy?.let { setLoadErrorHandlingPolicy(it) }
    }

  /** Resets the retry timer. Call when track changes. */
  fun resetRetryTimer() {
    customRetryPolicy?.reset()
  }

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

  override fun createMediaSource(mediaItem: MediaItem): MediaSource {
    // URL transformation is deferred to TransformingDataSource.open() which runs on
    // ExoPlayer's IO thread. This prevents deadlocks when the JS thread is blocked by
    // synchronous Nitro calls (e.g., seekTo) while a media transform callback needs the
    // JS thread to resolve.

    // OkHttp rather than DefaultHttpDataSource, for how each treats a redirect.
    // DefaultHttpDataSource re-applies the request headers on every hop, handing
    // an Authorization header to whatever host the first one named. OkHttp drops
    // it once the hop leaves the origin (host, port or scheme) while still
    // following http<->https.
    val httpFactory =
      OkHttpDataSource.Factory(mediaHttpClient).apply {
        // Deliberately NOT setUserAgent(): it is applied last via addHeader(),
        // which appends, so a request already carrying one would send two. As a
        // default request property it is merged under dataSpec.httpRequestHeaders
        // and applied with header(), letting TransformingDataSource's per-request
        // override win.
        setDefaultRequestProperties(mapOf("User-Agent" to DEFAULT_USER_AGENT))
        // Connect transfer listener for bandwidth measurement
        transferListener?.let { setTransferListener(it) }
      }
    val baseFactory: DataSource.Factory = DefaultDataSource.Factory(context, httpFactory)

    // Wrap with TransformingDataSource to resolve URLs on the IO thread
    val transformingFactory = TransformingDataSource.Factory(baseFactory, getRequestConfig)

    // Configure data source factory with optional caching
    val dataSourceFactory =
      cache?.let {
        CacheDataSource.Factory()
          .setCache(it)
          .setUpstreamDataSourceFactory(transformingFactory)
          .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
      } ?: transformingFactory

    // Use DefaultMediaSourceFactory which supports HLS, DASH, and progressive media
    return mediaFactory.setDataSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
  }
}
