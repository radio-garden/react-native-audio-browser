package com.doublesymmetry.trackplayer.model

import com.doublesymmetry.trackplayer.option.PlayerCapability
import com.margelo.nitro.audiobrowser.UpdateOptions
import com.margelo.nitro.audiobrowser.AndroidUpdateOptions
import com.margelo.nitro.audiobrowser.RatingType as NitroRatingType
import com.margelo.nitro.audiobrowser.AppKilledPlaybackBehavior

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
  fun updateFromNitro(options: UpdateOptions) {
    // Update jump intervals
    options.forwardJumpInterval?.let { forwardJumpInterval = it }
    options.backwardJumpInterval?.let { backwardJumpInterval = it }
    options.progressUpdateEventInterval?.let { progressUpdateEventInterval = it }

    // Update capabilities
    options.capabilities?.let { nitroCaps ->
      capabilities = nitroCaps.map { PlayerCapability.fromNitro(it) }
    }

    // Update Android-specific options
    options.android?.let { androidOptions ->
      // Convert rating type
      ratingType = when (androidOptions.ratingType) {
        NitroRatingType.HEART -> RatingType.HEART
        NitroRatingType.THUMBS_UP_DOWN -> RatingType.THUMBS_UP_DOWN
        NitroRatingType._3_STARS -> RatingType.THREE_STARS
        NitroRatingType._4_STARS -> RatingType.FOUR_STARS
        NitroRatingType._5_STARS -> RatingType.FIVE_STARS
        NitroRatingType.PERCENTAGE -> RatingType.PERCENTAGE
          null -> null
      }

      // Convert app killed playback behavior
      androidOptions.appKilledPlaybackBehavior?.let {
        appKilledPlaybackBehavior = it
      }

      // Update boolean options
      skipSilence = androidOptions.skipSilence ?: skipSilence
      shuffle = androidOptions.shuffle ?: shuffle

      // Convert notification capabilities
      notificationCapabilities = androidOptions.notificationCapabilities?.map { PlayerCapability.fromNitro(it) }
    }
  }

  fun toNitro(): UpdateOptions {
    // Convert capabilities
    val nitroCapabilities = capabilities.map { it.toNitro() }.toTypedArray()

    // Convert notification capabilities
    val nitroNotificationCapabilities = notificationCapabilities?.map { it.toNitro() }?.toTypedArray()

    // Create Android options
    val androidOptions = AndroidUpdateOptions(
      appKilledPlaybackBehavior = appKilledPlaybackBehavior,
      skipSilence = skipSilence,
      shuffle = shuffle,
      ratingType = ratingType?.let { rating ->
        when (rating) {
          RatingType.HEART -> NitroRatingType.HEART
          RatingType.THUMBS_UP_DOWN -> NitroRatingType.THUMBS_UP_DOWN
          RatingType.THREE_STARS -> NitroRatingType._3_STARS
          RatingType.FOUR_STARS -> NitroRatingType._4_STARS
          RatingType.FIVE_STARS -> NitroRatingType._5_STARS
          RatingType.PERCENTAGE -> NitroRatingType.PERCENTAGE
        }
      },
      notificationCapabilities = nitroNotificationCapabilities
    )

    return UpdateOptions(
      android = androidOptions,
      ios = null, // iOS options not handled in this class
      forwardJumpInterval = forwardJumpInterval,
      backwardJumpInterval = backwardJumpInterval,
      progressUpdateEventInterval = progressUpdateEventInterval,
      capabilities = nitroCapabilities
    )
  }

}
