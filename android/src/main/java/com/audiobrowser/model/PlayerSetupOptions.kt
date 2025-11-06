package com.audiobrowser.model

import androidx.media3.exoplayer.DefaultLoadControl
import com.margelo.nitro.audiobrowser.AndroidAudioContentType
import com.margelo.nitro.audiobrowser.AndroidPlayerWakeMode
import com.margelo.nitro.audiobrowser.PartialSetupPlayerOptions
import com.margelo.nitro.audiobrowser.Variant_Boolean_AndroidAudioOffloadSettings

/**
 * Audio offload preferences for power-efficient playback. When this object exists, offload is
 * considered enabled.
 */
data class AudioOffloadOptions(
  val gaplessSupportRequired: Boolean = true,
  val rateChangeSupportRequired: Boolean = true,
)

/**
 * Setup options for the AudioBrowser that are applied once during player initialization. These
 * options configure the audio engine and system-level behavior.
 */
data class PlayerSetupOptions(
  // Cross-platform audio engine buffer options
  var minBuffer: Double = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS.toDouble(),
  var audioContentType: AndroidAudioContentType = AndroidAudioContentType.MUSIC,

  // Android-specific options (all under android.* in JS)
  var maxBuffer: Double = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS.toDouble(),
  var playBuffer: Double = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS.toDouble(),
  var rebufferBuffer: Double? = null,
  var backBuffer: Double = DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS.toDouble(),
  var maxCacheSize: Double = 0.0,
  var handleAudioBecomingNoisy: Boolean = true,
  var wakeMode: AndroidPlayerWakeMode = AndroidPlayerWakeMode.NONE,
  var audioOffload: AudioOffloadOptions? = null,
) {
  fun update(options: PartialSetupPlayerOptions) {
    // Cross-platform audio engine options
    options.minBuffer?.let { minBuffer = it }

    // Android-specific options
    options.android?.let { android ->
      android.maxBuffer?.let { maxBuffer = it }
      android.playBuffer?.let { playBuffer = it }
      android.rebufferBuffer?.let { rebufferBuffer = it }
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
  }
}
