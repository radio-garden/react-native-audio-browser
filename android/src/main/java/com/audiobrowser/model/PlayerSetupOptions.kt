package com.audiobrowser.model

import androidx.media3.exoplayer.DefaultLoadControl
import com.margelo.nitro.audiobrowser.AndroidAudioContentType
import com.margelo.nitro.audiobrowser.AndroidPlayerWakeMode
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__optional_NowPlayingUpdate____FormatNowPlayingParams as NowPlayingFormatter
import com.margelo.nitro.audiobrowser.NativeSetupPlayerOptions
import com.margelo.nitro.audiobrowser.RemoteOutputsOptions
import com.margelo.nitro.audiobrowser.Variant_Boolean_AndroidAudioOffloadSettings
import com.margelo.nitro.audiobrowser.Variant_Boolean_RetryConfig

/** Offload preferences; [PlayerSetupOptions.audioOffload] being null turns offload off. */
data class AudioOffloadOptions(
  val gaplessSupportRequired: Boolean = true,
  val rateChangeSupportRequired: Boolean = true,
)

/** How the player retries a load error (network failure, timeout). */
sealed class RetryPolicy {
  /** ExoPlayer's default: a few retries, then the error surfaces. */
  data object Default : RetryPolicy()

  /**
   * Retries with exponential backoff, with no attempt cap, until a duration bound runs out; a null
   * bound takes RetryLoadErrorHandlingPolicy's default.
   */
  data class Infinite(
    val maxRetryDurationMs: Long? = null,
    val firstConnectMaxRetryDurationMs: Long? = null,
  ) : RetryPolicy()

  /** Retries up to [maxRetries] times with exponential backoff. */
  data class Limited(
    val maxRetries: Int,
    val maxRetryDurationMs: Long? = null,
    val firstConnectMaxRetryDurationMs: Long? = null,
  ) : RetryPolicy()
}

/** The options [Player.setup] builds the engine from; see [engineOptions] for what rebuilds it. */
data class PlayerSetupOptions(
  // The buffer fields are durations in milliseconds.
  var minBuffer: Double = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS.toDouble(),
  var audioContentType: AndroidAudioContentType = AndroidAudioContentType.MUSIC,
  var maxBuffer: Double = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS.toDouble(),
  var playBuffer: Double = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS.toDouble(),
  // null lets AutomaticBufferManager tune it.
  var rebufferBuffer: Double? = null,
  var backBuffer: Double = DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS.toDouble(),
  // In megabytes; 0 disables the cache.
  var maxCacheSize: Double = 0.0,
  var handleAudioBecomingNoisy: Boolean = true,
  var wakeMode: AndroidPlayerWakeMode = AndroidPlayerWakeMode.NONE,
  var audioOffload: AudioOffloadOptions? = null,
  var retryPolicy: RetryPolicy = RetryPolicy.Default,
  // Keeps the media session through a terminal error so external controllers (Android Auto) keep
  // their transport controls. On by default to match iOS, where the session always survives.
  var keepSessionAliveOnError: Boolean = true,
  var autoUpdateNowPlayingMetadata: Boolean = true,
  var nowPlayingMetadataFormatter: NowPlayingFormatter? = null,
  var remoteOutputs: RemoteOutputsOptions? = null,
) {
  val automaticBuffer: Boolean
    get() = rebufferBuffer == null

  /**
   * A copy that equals another's when both build the same engine: without the now-playing options
   * and the remote outputs, which apply without a rebuild. A formatter is a new object on every
   * setup call, so leaving it in would rebuild every time.
   */
  fun engineOptions() =
    copy(
      autoUpdateNowPlayingMetadata = true,
      nowPlayingMetadataFormatter = null,
      remoteOutputs = null,
    )

  fun update(options: NativeSetupPlayerOptions) {
    options.android?.let { android ->
      android.minBuffer?.let { minBuffer = it }
      android.maxBuffer?.let { maxBuffer = it }
      android.playBuffer?.let { playBuffer = it }
      android.rebufferBuffer?.let { rebufferBuffer = it.asSecondOrNull() }
      android.backBuffer?.let { backBuffer = it }
      android.maxCacheSize?.let { maxCacheSize = it }
      android.handleAudioBecomingNoisy?.let { handleAudioBecomingNoisy = it }
      android.audioContentType?.let { audioContentType = it }
      android.wakeMode?.let { wakeMode = it }
      android.audioOffload?.let {
        audioOffload =
          when (android.audioOffload) {
            is Variant_Boolean_AndroidAudioOffloadSettings.First -> {
              if (android.audioOffload.value) AudioOffloadOptions() else null
            }
            is Variant_Boolean_AndroidAudioOffloadSettings.Second -> {
              val settings = android.audioOffload.value
              AudioOffloadOptions(
                gaplessSupportRequired = settings.gaplessSupportRequired ?: false,
                rateChangeSupportRequired = settings.rateChangeSupportRequired ?: false,
              )
            }
          }
      }
    }

    options.retry?.let {
      retryPolicy =
        when (it) {
          is Variant_Boolean_RetryConfig.First -> {
            if (it.value) RetryPolicy.Infinite() else RetryPolicy.Default
          }
          is Variant_Boolean_RetryConfig.Second -> {
            val maxRetries = it.value.maxRetries?.toInt()
            val maxDurationMs = it.value.maxRetryDurationMs?.toLong()
            val firstConnectMs = it.value.firstConnectMaxRetryDurationMs?.toLong()
            if (maxRetries == null)
            // No attempt cap: retry until a duration bound runs out.
            RetryPolicy.Infinite(
                maxRetryDurationMs = maxDurationMs,
                firstConnectMaxRetryDurationMs = firstConnectMs,
              )
            else
              RetryPolicy.Limited(
                maxRetries = maxRetries,
                maxRetryDurationMs = maxDurationMs,
                firstConnectMaxRetryDurationMs = firstConnectMs,
              )
          }
        }
    }

    options.keepSessionAliveOnError?.let { keepSessionAliveOnError = it }
    options.autoUpdateNowPlayingMetadata?.let { autoUpdateNowPlayingMetadata = it }
    options.nowPlayingMetadataFormatter?.let { nowPlayingMetadataFormatter = it }
    // Unlike the fields above, an absent value turns remote outputs off.
    remoteOutputs = options.remoteOutputs
  }
}
