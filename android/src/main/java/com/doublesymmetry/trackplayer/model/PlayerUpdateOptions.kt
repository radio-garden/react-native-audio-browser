package com.doublesymmetry.trackplayer.model

import com.doublesymmetry.trackplayer.option.PlayerCapability
import com.doublesymmetry.trackplayer.option.PlayerRepeatMode
import com.doublesymmetry.trackplayer.model.AppKilledPlaybackBehavior
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap

/**
 * Update options for the TrackPlayer that can be changed at runtime. These options control player
 * behavior and capabilities that can be modified during playback.
 */
data class PlayerUpdateOptions(
  // Jump intervals
  var forwardJumpInterval: Double = 15.0,
  var backwardJumpInterval: Double = 15.0,
  var progressUpdateEventInterval: Double? = null,

  // Rating and capabilities
  var capabilities: List<PlayerCapability> =
    listOf(
      PlayerCapability.PLAY,
      PlayerCapability.PAUSE,
      PlayerCapability.SKIP_TO_NEXT,
      PlayerCapability.SKIP_TO_PREVIOUS,
      PlayerCapability.SEEK_TO,
    ),
  var notificationCapabilities: List<PlayerCapability>? = null,

  // Android-specific runtime options (all under android.* in JS)
  var ratingType: RatingType? = null,
  var appKilledPlaybackBehavior: AppKilledPlaybackBehavior = AppKilledPlaybackBehavior.STOP_PLAYBACK_AND_REMOVE_NOTIFICATION,
  var skipSilence: Boolean = false,
  var shuffle: Boolean = false,
) {

  fun updateFromBridge(map: ReadableMap?) {
    if (map == null) return

    if (map.hasKey("forwardJumpInterval")) {
      forwardJumpInterval = map.getDouble("forwardJumpInterval")
    }
    if (map.hasKey("backwardJumpInterval")) {
      backwardJumpInterval = map.getDouble("backwardJumpInterval")
    }
    if (map.hasKey("progressUpdateEventInterval")) {
      progressUpdateEventInterval =
        if (map.isNull("progressUpdateEventInterval")) {
          null
        } else {
          map.getDouble("progressUpdateEventInterval")
        }
    }

    map.getArray("capabilities")?.let { arr ->
      capabilities =
        (0 until arr.size()).mapNotNull { index ->
          val value = arr.getString(index) ?: return@mapNotNull null
          PlayerCapability.fromString(value)
            ?: throw IllegalArgumentException("Invalid capability value: $value")
        }
    }

    // Android-specific runtime options (all under android.*)
    val androidMap = if (map.hasKey("android")) map.getMap("android") else null
    androidMap?.let { android ->
      if (android.hasKey("ratingType")) {
        ratingType = android.getString("ratingType")?.let { RatingType.fromString(it) }
      }
      if (android.hasKey("appKilledPlaybackBehavior")) {
        appKilledPlaybackBehavior = android.getString("appKilledPlaybackBehavior")?.let {
          AppKilledPlaybackBehavior.values().find { enum -> enum.string == it }
        } ?: AppKilledPlaybackBehavior.STOP_PLAYBACK_AND_REMOVE_NOTIFICATION
      }
      if (android.hasKey("skipSilence")) {
        skipSilence = android.getBoolean("skipSilence")
      }
      if (android.hasKey("shuffle")) {
        shuffle = android.getBoolean("shuffle")
      }
      if (android.hasKey("notificationCapabilities")) {
        notificationCapabilities =
          if (android.isNull("notificationCapabilities")) {
            null // Explicitly set to null - reset to default behavior
          } else {
            android.getArray("notificationCapabilities")?.let { arr ->
              (0 until arr.size()).mapNotNull { index ->
                val value = arr.getString(index) ?: return@mapNotNull null
                PlayerCapability.fromString(value)
                  ?: throw IllegalArgumentException("Invalid notificationCapability value: $value")
              }
            }
          }
      }
    }
  }

  fun toBridge(): WritableMap {
    val result = Arguments.createMap()

    // Add jump intervals (always include these core values)
    result.putDouble("forwardJumpInterval", forwardJumpInterval)
    result.putDouble("backwardJumpInterval", backwardJumpInterval)

    // Add progress update interval (always include, null means disabled)
    val interval = progressUpdateEventInterval
    if (interval != null) {
      result.putDouble("progressUpdateEventInterval", interval)
    } else {
      result.putNull("progressUpdateEventInterval")
    }

    // Add capabilities (always include, even if empty)
    val capabilitiesArray = Arguments.createArray()
    capabilities.forEach { cap -> capabilitiesArray.pushString(cap.string) }
    result.putArray("capabilities", capabilitiesArray)

    // Add Android-specific options (always included since appKilledPlaybackBehavior is always present)
    val androidOptions = Arguments.createMap()

    ratingType?.let { androidOptions.putString("ratingType", it.string) }
    androidOptions.putString("appKilledPlaybackBehavior", appKilledPlaybackBehavior.string)
    androidOptions.putBoolean("skipSilence", skipSilence)
    androidOptions.putBoolean("shuffle", shuffle)

    // Add notification capabilities under android namespace if set
    notificationCapabilities?.let { caps ->
      val notificationCapsArray = Arguments.createArray()
      caps.forEach { cap -> notificationCapsArray.pushString(cap.string) }
      androidOptions.putArray("notificationCapabilities", notificationCapsArray)
    }

    result.putMap("android", androidOptions)

    return result
  }

}
