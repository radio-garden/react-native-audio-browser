package com.audiobrowser.model

import com.audiobrowser.option.AudioContentType
import com.audiobrowser.option.PlayerWakeMode
import com.margelo.nitro.audiobrowser.PlayerOptions
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
  var minBuffer: Double? = null,
  var audioContentType: AudioContentType = AudioContentType.MUSIC,

  // Android-specific options (all under android.* in JS)
  var maxBuffer: Double? = null,
  var playBuffer: Double? = null,
  var rebufferBuffer: Double? = null,
  var backBuffer: Double? = null,
  var maxCacheSize: Double = 0.0,
  var handleAudioBecomingNoisy: Boolean = true,
  var wakeMode: PlayerWakeMode = PlayerWakeMode.NONE,
  var audioOffload: AudioOffloadOptions? = null,
) {
  fun update(options: PlayerOptions) {
    // Cross-platform audio engine options
    options.minBuffer?.let { minBuffer = it }

    // Android-specific options
    options.android?.let { android ->
      maxBuffer = android.maxBuffer
      playBuffer = android.playBuffer
      rebufferBuffer = android.rebufferBuffer
      backBuffer = android.backBuffer
      maxCacheSize = android.maxCacheSize
      handleAudioBecomingNoisy = android.handleAudioBecomingNoisy

      // Convert audio content type
      audioContentType = AudioContentType.fromNitro(android.audioContentType)

      // Convert audio offload options
      audioOffload = when (android.audioOffload) {
        is Variant_Boolean_AndroidAudioOffloadSettings.First -> {
          if (android.audioOffload.value) AudioOffloadOptions() else null
        }
        is Variant_Boolean_AndroidAudioOffloadSettings.Second -> {
          val settings = android.audioOffload.value
          AudioOffloadOptions(
            gaplessSupportRequired = settings.gaplessSupportRequired ?: false,
            rateChangeSupportRequired = settings.rateChangeSupportRequired ?: false
          )
        }
      }
    }
  }
}
