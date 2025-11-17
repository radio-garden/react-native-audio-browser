package com.audiobrowser.model

import com.margelo.nitro.audiobrowser.AndroidUpdateOptions
import com.margelo.nitro.audiobrowser.AppKilledPlaybackBehavior
import com.margelo.nitro.audiobrowser.Capability
import com.margelo.nitro.audiobrowser.NativeUpdateOptions
import com.margelo.nitro.audiobrowser.RatingType as NitroRatingType
import com.margelo.nitro.audiobrowser.UpdateOptions
import com.margelo.nitro.audiobrowser.Variant_NullType_Array_Capability_
import com.margelo.nitro.audiobrowser.Variant_NullType_Double

/**
 * Update options for the AudioBrowser that can be changed at runtime. These options control player
 * behavior and capabilities that can be modified during playback.
 */
data class PlayerUpdateOptions(
  // Jump intervals
  var forwardJumpInterval: Double = 15.0,
  var backwardJumpInterval: Double = 15.0,
  var progressUpdateEventInterval: Double? = null,

  // Rating and capabilities
  var capabilities: List<Capability> =
    listOf(
      Capability.PLAY,
      Capability.PAUSE,
      Capability.SKIP_TO_NEXT,
      Capability.SKIP_TO_PREVIOUS,
      Capability.SEEK_TO,
    ),
  var notificationCapabilities: List<Capability>? = null,

  // Android-specific runtime options (all under android.* in JS)
  var ratingType: NitroRatingType? = null,
  var appKilledPlaybackBehavior: AppKilledPlaybackBehavior =
    AppKilledPlaybackBehavior.STOP_PLAYBACK_AND_REMOVE_NOTIFICATION,
  var skipSilence: Boolean = false,
  var shuffle: Boolean = false,
) {
  fun updateFromBridge(options: NativeUpdateOptions) {
    options.forwardJumpInterval?.let { forwardJumpInterval = it }
    options.backwardJumpInterval?.let { backwardJumpInterval = it }

    options.progressUpdateEventInterval?.let { variant ->
      progressUpdateEventInterval =
        when (variant) {
          is Variant_NullType_Double.First -> null
          is Variant_NullType_Double.Second -> variant.value
        }
    }

    options.capabilities?.let { nitroCaps -> capabilities = nitroCaps.toList() }

    // Update Android-specific options
    options.android?.let { androidOptions ->
      // Convert rating type
      androidOptions.ratingType?.let { ratingType = it }

      androidOptions.appKilledPlaybackBehavior?.let { appKilledPlaybackBehavior = it }

      // Update boolean options
      androidOptions.skipSilence?.let { skipSilence = it }

      androidOptions.shuffle?.let { shuffle = it }

      androidOptions.notificationCapabilities?.let { variant ->
        notificationCapabilities =
          when (variant) {
            is Variant_NullType_Array_Capability_.First -> null
            is Variant_NullType_Array_Capability_.Second -> variant.value.toList()
          }
      }
    }
  }

  fun toNitro(): UpdateOptions {
    // Convert capabilities
    val nitroCapabilities = capabilities.toTypedArray()

    // Convert notification capabilities
    val nitroNotificationCapabilities = notificationCapabilities?.toTypedArray()

    // Create Android options
    val androidOptions =
      AndroidUpdateOptions(
        appKilledPlaybackBehavior = appKilledPlaybackBehavior,
        skipSilence = skipSilence,
        shuffle = shuffle,
        ratingType = ratingType,
        notificationCapabilities = nitroNotificationCapabilities?.let { Variant_NullType_Array_Capability_.create(it) }
      )

    return UpdateOptions(
      android = androidOptions,
      ios = null, // iOS options not handled in this class
      forwardJumpInterval = forwardJumpInterval,
      backwardJumpInterval = backwardJumpInterval,
      progressUpdateEventInterval = progressUpdateEventInterval?.let { Variant_NullType_Double.create(it) },
      capabilities = nitroCapabilities,
    )
  }
}
