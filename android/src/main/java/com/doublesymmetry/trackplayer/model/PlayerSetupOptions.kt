package com.doublesymmetry.trackplayer.model

import com.doublesymmetry.trackplayer.option.AudioContentType
import com.doublesymmetry.trackplayer.option.PlayerWakeMode
import com.facebook.react.bridge.ReadableMap

/**
 * Audio offload preferences for power-efficient playback. When this object exists, offload is
 * considered enabled.
 */
data class AudioOffloadOptions(
  val gaplessSupportRequired: Boolean = true,
  val rateChangeSupportRequired: Boolean = true,
) {
  companion object {
    fun fromBridge(value: Any?): AudioOffloadOptions? {
      return when (value) {
        true -> AudioOffloadOptions() // Default settings
        false,
        null -> null // Disabled
        is ReadableMap -> {
          val gaplessSupportRequired =
            if (value.hasKey("gaplessSupportRequired")) {
              value.getBoolean("gaplessSupportRequired")
            } else true
          val rateChangeSupportRequired =
            if (value.hasKey("rateChangeSupportRequired")) {
              value.getBoolean("rateChangeSupportRequired")
            } else true
          AudioOffloadOptions(gaplessSupportRequired, rateChangeSupportRequired)
        }
        else ->
          throw IllegalArgumentException(
            "audioOffload must be a boolean or object, got: ${value?.javaClass?.simpleName}"
          )
      }
    }
  }
}

/**
 * Setup options for the TrackPlayer that are applied once during player initialization. These
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
  fun updateFromBridge(map: ReadableMap?) {
    if (map == null) return

    // Cross-platform audio engine options
    if (map.hasKey("minBuffer")) {
      minBuffer = map.getDouble("minBuffer")
    }

    // Android-specific options (all under android.*)
    val androidMap = if (map.hasKey("android")) map.getMap("android") else null
    androidMap?.let { android ->
      android.getString("audioContentType")?.let {
        audioContentType = AudioContentType.fromString(it)
      }
      if (android.hasKey("maxBuffer")) {
        maxBuffer = android.getDouble("maxBuffer")
      }
      if (android.hasKey("playBuffer")) {
        playBuffer = android.getDouble("playBuffer")
      }
      if (android.hasKey("rebufferBuffer")) {
        rebufferBuffer = android.getDouble("rebufferBuffer")
      }
      if (android.hasKey("backBuffer")) {
        backBuffer = android.getDouble("backBuffer")
      }
      if (android.hasKey("maxCacheSize")) {
        maxCacheSize = android.getDouble("maxCacheSize")
      }
      if (android.hasKey("handleAudioBecomingNoisy")) {
        handleAudioBecomingNoisy = android.getBoolean("handleAudioBecomingNoisy")
      }
      if (android.hasKey("wakeMode")) {
        val value = android.getInt("wakeMode")
        wakeMode =
          PlayerWakeMode.entries.getOrNull(value)
            ?: throw IllegalArgumentException(
              "Invalid wakeMode value: $value (valid range: 0-${PlayerWakeMode.entries.size - 1})"
            )
      }
      if (android.hasKey("audioOffload")) {
        val audioOffloadValue = android.getDynamic("audioOffload")
        audioOffload =
          when (audioOffloadValue.type) {
            com.facebook.react.bridge.ReadableType.Boolean -> {
              AudioOffloadOptions.fromBridge(audioOffloadValue.asBoolean())
            }
            com.facebook.react.bridge.ReadableType.Map -> {
              AudioOffloadOptions.fromBridge(audioOffloadValue.asMap())
            }
            com.facebook.react.bridge.ReadableType.Null -> null
            else ->
              throw IllegalArgumentException(
                "audioOffload must be a boolean or object, got: ${audioOffloadValue.type}"
              )
          }
      }
    }
  }
}
