package com.audiobrowser.model

import com.audiobrowser.extension.NumberExt.Companion.toMilliseconds
import com.audiobrowser.option.AudioContentType
import com.audiobrowser.option.BufferOptions
import com.audiobrowser.option.PlayerCapability
import com.audiobrowser.option.PlayerOptions
import com.audiobrowser.option.PlayerWakeMode
import com.facebook.react.bridge.ReadableMap

data class TrackPlayerOptions(
  val forwardJumpInterval: Double = 15.0,
  val backwardJumpInterval: Double = 15.0,
  val progressUpdateEventInterval: Double = -1.0,
  val ratingType: RatingType? = null,
  val capabilities: List<PlayerCapability>? = null,
  val notificationCapabilities: List<PlayerCapability>? = null,

  // Audio engine options
  val minBuffer: Double? = null,
  val maxBuffer: Double? = null,
  val playBuffer: Double? = null,
  val rebufferBuffer: Double? = null,
  val backBuffer: Double? = null,
  val maxCacheSize: Double = 0.0,
  val audioContentType: AudioContentType = AudioContentType.MUSIC,
  val handleAudioBecomingNoisy: Boolean = true,
  val autoHandleInterruptions: Boolean = true,
  val wakeMode: PlayerWakeMode = PlayerWakeMode.NONE,

  // Android-specific options
  val audioOffload: Boolean? = null,
  val skipSilence: Boolean? = null,
  val appKilledPlaybackBehavior: String? = null,
  val shuffle: Boolean? = null,
) {
  companion object {
    fun fromBridge(map: ReadableMap?): TrackPlayerOptions {
      if (map == null) return TrackPlayerOptions()

      val capabilities =
        map.getArray("capabilities")?.let { arr ->
          (0 until arr.size()).mapNotNull { index ->
            val value = arr.getString(index) ?: return@mapNotNull null
            PlayerCapability.fromString(value)
              ?: throw IllegalArgumentException("Invalid capability value: $value")
          }
        }

      val notificationCapabilities =
        map.getArray("notificationCapabilities")?.let { arr ->
          (0 until arr.size()).mapNotNull { index ->
            val value = arr.getString(index) ?: return@mapNotNull null
            PlayerCapability.fromString(value)
              ?: throw IllegalArgumentException("Invalid notificationCapability value: $value")
          }
        }

      val androidMap = if (map.hasKey("android")) map.getMap("android") else null

      return TrackPlayerOptions(
        forwardJumpInterval =
          if (map.hasKey("forwardJumpInterval")) map.getDouble("forwardJumpInterval") else 15.0,
        backwardJumpInterval =
          if (map.hasKey("backwardJumpInterval")) map.getDouble("backwardJumpInterval") else 15.0,
        progressUpdateEventInterval =
          if (map.hasKey("progressUpdateEventInterval"))
            map.getDouble("progressUpdateEventInterval")
          else -1.0,
        ratingType = map.getString("ratingType")?.let { RatingType.fromString(it) },
        capabilities = capabilities,
        notificationCapabilities = notificationCapabilities,

        // Audio engine options
        minBuffer = if (map.hasKey("minBuffer")) map.getDouble("minBuffer") else null,
        maxBuffer = if (map.hasKey("maxBuffer")) map.getDouble("maxBuffer") else null,
        playBuffer = if (map.hasKey("playBuffer")) map.getDouble("playBuffer") else null,
        rebufferBuffer =
          if (map.hasKey("rebufferBuffer")) map.getDouble("rebufferBuffer") else null,
        backBuffer = if (map.hasKey("backBuffer")) map.getDouble("backBuffer") else null,
        maxCacheSize = if (map.hasKey("maxCacheSize")) map.getDouble("maxCacheSize") else 0.0,
        audioContentType =
          AudioContentType.fromString(map.getString("audioContentType") ?: "music"),
        handleAudioBecomingNoisy =
          if (map.hasKey("handleAudioBecomingNoisy")) map.getBoolean("handleAudioBecomingNoisy")
          else true,
        autoHandleInterruptions =
          if (map.hasKey("autoHandleInterruptions")) map.getBoolean("autoHandleInterruptions")
          else true,
        wakeMode =
          if (map.hasKey("wakeMode")) {
            val value = map.getInt("wakeMode")
            PlayerWakeMode.entries.getOrNull(value)
              ?: throw IllegalArgumentException(
                "Invalid wakeMode value: $value (valid range: 0-${PlayerWakeMode.entries.size - 1})"
              )
          } else PlayerWakeMode.NONE,

        // Android-specific options
        audioOffload =
          if (androidMap?.hasKey("audioOffload") == true) androidMap.getBoolean("audioOffload")
          else null,
        skipSilence =
          if (androidMap?.hasKey("skipSilence") == true) androidMap.getBoolean("skipSilence")
          else null,
        appKilledPlaybackBehavior =
          if (androidMap?.hasKey("appKilledPlaybackBehavior") == true)
            androidMap.getString("appKilledPlaybackBehavior")
          else null,
        shuffle =
          if (androidMap?.hasKey("shuffle") == true) androidMap.getBoolean("shuffle") else null,
      )
    }
  }

  fun toPlayerOptions(): PlayerOptions {
    return PlayerOptions(
      audioContentType = audioContentType,
      bufferOptions =
        BufferOptions(
          minBuffer?.toMilliseconds()?.toInt(),
          maxBuffer?.toMilliseconds()?.toInt(),
          playBuffer?.toMilliseconds()?.toInt(),
          rebufferBuffer?.toMilliseconds()?.toInt(),
          backBuffer?.toMilliseconds()?.toInt(),
        ),
      cacheSizeKb = maxCacheSize.toLong(),
      handleAudioBecomingNoisy = handleAudioBecomingNoisy,
      interceptPlayerActionsTriggeredExternally = true,
      skipSilence = skipSilence ?: false,
      wakeMode = wakeMode,
      forwardJumpInterval = forwardJumpInterval,
      backwardJumpInterval = backwardJumpInterval,
    )
  }
}
