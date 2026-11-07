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
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.Track
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
  private val onRetryPending: ((exception: IOException) -> Unit)? = null,
  private val transferListener: TransferListener? = null,
  private val getRequestConfig: (originalUrl: String, track: Track) -> MediaRequestConfig?,
) : MediaSource.Factory {

  companion object {
    private const val DEFAULT_USER_AGENT = "react-native-audio-browser"

    /**
     * The client media requests go out on, one per process so connections are reused. Not
     * `HttpClient`'s browse client, whose read timeout is sized for JSON and would cut a long media
     * read short. The timeouts are media3's own defaults, which stall detection and
     * [RetryLoadErrorHandlingPolicy] are tuned to.
     */
    private val mediaHttpClient: OkHttpClient by lazy {
      OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .addNetworkInterceptor(CrossOriginHeaderStripper)
        .build()
    }
  }

  private val extractorsFactory = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)

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

  /** Restarts the retry policy's time budgets. */
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
    // The URL is resolved in TransformingDataSource.open(), on ExoPlayer's IO thread, not here:
    // the request config callback needs the JS thread, which a synchronous Nitro call (seekTo)
    // can be blocking while it waits on the player, a deadlock.

    // OkHttp rather than DefaultHttpDataSource, for redirects: DefaultHttpDataSource re-applies the
    // request headers on every hop, handing an Authorization header to whatever host the first one
    // names. OkHttp drops that header itself, and [CrossOriginHeaderStripper] drops the rest.
    val httpFactory =
      OkHttpDataSource.Factory(mediaHttpClient).apply {
        // Not setUserAgent(): it is applied last with addHeader(), which appends, so a request
        // already carrying one would send two. As a default request property it is merged under
        // dataSpec.httpRequestHeaders and applied with header(), so TransformingDataSource's
        // per-request override wins.
        setDefaultRequestProperties(mapOf("User-Agent" to DEFAULT_USER_AGENT))
        transferListener?.let { setTransferListener(it) }
      }
    val baseFactory: DataSource.Factory = DefaultDataSource.Factory(context, httpFactory)

    val track = TrackFactory.fromMedia3(mediaItem)
    val transformingFactory =
      TransformingDataSource.Factory(baseFactory) { url -> getRequestConfig(url, track) }

    val dataSourceFactory =
      cache?.let {
        CacheDataSource.Factory()
          .setCache(it)
          .setUpstreamDataSourceFactory(transformingFactory)
          .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
      } ?: transformingFactory

    return mediaFactory.setDataSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
  }
}
