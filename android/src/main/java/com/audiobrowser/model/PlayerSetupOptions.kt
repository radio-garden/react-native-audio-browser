package com.audiobrowser.model

import androidx.media3.exoplayer.DefaultLoadControl
import com.margelo.nitro.audiobrowser.AndroidAudioContentType
import com.margelo.nitro.audiobrowser.AndroidPlayerWakeMode
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__optional_NowPlayingUpdate____FormatNowPlayingParams as NowPlayingFormatter
import com.margelo.nitro.audiobrowser.NativeSetupPlayerOptions
import com.margelo.nitro.audiobrowser.Variant_Boolean_AndroidAudioOffloadSettings
import com.margelo.nitro.audiobrowser.Variant_Boolean_RetryConfig

/**
 * Audio offload preferences for power-efficient playback. When this object exists, offload is
 * considered enabled.
 */
data class AudioOffloadOptions(
  val gaplessSupportRequired: Boolean = true,
  val rateChangeSupportRequired: Boolean = true,
)

/** Retry policy for load errors (network failures, timeouts, etc.) */
sealed class RetryPolicy {
  /** Use ExoPlayer's default behavior (limited retries, surfaces errors) */
  data object Default : RetryPolicy()

  /** Retry indefinitely with exponential backoff */
  data class Infinite(
    val maxRetryDurationMs: Long? = null,
    val firstConnectMaxRetryDurationMs: Long? = null,
  ) : RetryPolicy()

  /** Retry up to maxRetries times with exponential backoff */
  data class Limited(
    val maxRetries: Int,
    val maxRetryDurationMs: Long? = null,
    val firstConnectMaxRetryDurationMs: Long? = null,
  ) : RetryPolicy()
}

/**
 * Setup options for the AudioBrowser that are applied once during player initialization. These
 * options configure the audio engine and system-level behavior.
 */
data class PlayerSetupOptions(
  // Android-specific options (all under android.* in JS)
  var minBuffer: Double = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS.toDouble(),
  var audioContentType: AndroidAudioContentType = AndroidAudioContentType.MUSIC,
  var maxBuffer: Double = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS.toDouble(),
  var playBuffer: Double = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS.toDouble(),
  var rebufferBuffer: Double? = null,
  var backBuffer: Double = DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS.toDouble(),
  var maxCacheSize: Double = 0.0,
  var handleAudioBecomingNoisy: Boolean = true,
  var wakeMode: AndroidPlayerWakeMode = AndroidPlayerWakeMode.NONE,
  var audioOffload: AudioOffloadOptions? = null,
  var retryPolicy: RetryPolicy = RetryPolicy.Default,
  // Keep the media session alive & controllable through a terminal playback error so external
  // controllers (Android Auto) keep their transport controls instead of tearing the session down.
  // Default on: matches iOS, where the session always survives errors.
  var keepSessionAliveOnError: Boolean = true,
  // Whether the player publishes/refreshes track metadata on the now-playing surface.
  var autoUpdateNowPlayingMetadata: Boolean = true,
  // Optional JS formatter that customizes the now-playing title/subtitle from the track + live
  // timed metadata. When null, the default mapping is used.
  var nowPlayingMetadataFormatter: NowPlayingFormatter? = null,
) {
  /**
   * Whether automatic buffer management is enabled. True when rebufferBuffer is not explicitly set
   * (null).
   */
  val automaticBuffer: Boolean
    get() = rebufferBuffer == null

  fun update(options: NativeSetupPlayerOptions) {
    // Android-specific options
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

    // Top-level cross-platform options
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
            // No attempt cap = retry indefinitely, bounded only by the durations.
            if (maxRetries == null)
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
  }
}
