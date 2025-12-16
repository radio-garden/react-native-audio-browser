package com.audiobrowser.model

import com.margelo.nitro.audiobrowser.AndroidUpdateOptions
import com.margelo.nitro.audiobrowser.AppKilledPlaybackBehavior
import com.margelo.nitro.audiobrowser.NativeUpdateOptions
import com.margelo.nitro.audiobrowser.NotificationButtonLayout
import com.margelo.nitro.audiobrowser.PlayerCapabilities
import com.margelo.nitro.audiobrowser.RatingType as NitroRatingType
import com.margelo.nitro.audiobrowser.UpdateOptions
import com.margelo.nitro.audiobrowser.Variant_NullType_Double
import com.margelo.nitro.audiobrowser.Variant_NullType_NotificationButtonLayout

/**
 * Update options for the AudioBrowser that can be changed at runtime. These options control player
 * behavior and capabilities that can be modified during playback.
 */
data class PlayerUpdateOptions(
  // Jump intervals
  var forwardJumpInterval: Double = 15.0,
  var backwardJumpInterval: Double = 15.0,
  var progressUpdateEventInterval: Double? = null,

  // Player capabilities - most enabled by default, only false values disable
  // Exceptions: bookmark, jumpForward, jumpBackward default to false
  var capabilities: PlayerCapabilities = PlayerCapabilities(
    play = null, pause = null, stop = null, seekTo = null,
    skipToNext = null, skipToPrevious = null,
    jumpForward = false, jumpBackward = false,
    favorite = null, bookmark = false,
    shuffleMode = null, repeatMode = null, playbackRate = null
  ),

  // Notification button layout (null = derive from capabilities)
  var notificationButtons: NotificationButtonLayout? = null,

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

    options.capabilities?.let { newCaps ->
      capabilities = mergeCapabilities(capabilities, newCaps)
    }

    // Update Android-specific options
    options.android?.let { androidOptions ->
      // Convert rating type
      androidOptions.ratingType?.let { ratingType = it }

      androidOptions.appKilledPlaybackBehavior?.let { appKilledPlaybackBehavior = it }

      // Update boolean options
      androidOptions.skipSilence?.let { skipSilence = it }

      androidOptions.shuffle?.let { shuffle = it }

      // Handle notificationButtons - variant allows distinguishing undefined from null
      androidOptions.notificationButtons?.let { variant ->
        notificationButtons =
          when (variant) {
            is Variant_NullType_NotificationButtonLayout.First -> null
            is Variant_NullType_NotificationButtonLayout.Second -> variant.value
          }
      }
    }
  }

  fun toNitro(): UpdateOptions {
    // Create Android options
    val androidOptions =
      AndroidUpdateOptions(
        appKilledPlaybackBehavior = appKilledPlaybackBehavior,
        skipSilence = skipSilence,
        shuffle = shuffle,
        ratingType = ratingType,
        notificationButtons =
          notificationButtons?.let { Variant_NullType_NotificationButtonLayout.create(it) },
      )

    return UpdateOptions(
      android = androidOptions,
      ios = null, // iOS options not handled in this class
      forwardJumpInterval = forwardJumpInterval,
      backwardJumpInterval = backwardJumpInterval,
      progressUpdateEventInterval =
        progressUpdateEventInterval?.let { Variant_NullType_Double.create(it) },
      capabilities = capabilities,
      iosPlaybackRates = null, // iOS-only option
    )
  }

  /** Merge incoming capabilities with existing - only explicitly set values override */
  private fun mergeCapabilities(
    existing: PlayerCapabilities,
    incoming: PlayerCapabilities
  ): PlayerCapabilities {
    return PlayerCapabilities(
      play = incoming.play ?: existing.play,
      pause = incoming.pause ?: existing.pause,
      stop = incoming.stop ?: existing.stop,
      seekTo = incoming.seekTo ?: existing.seekTo,
      skipToNext = incoming.skipToNext ?: existing.skipToNext,
      skipToPrevious = incoming.skipToPrevious ?: existing.skipToPrevious,
      jumpForward = incoming.jumpForward ?: existing.jumpForward,
      jumpBackward = incoming.jumpBackward ?: existing.jumpBackward,
      favorite = incoming.favorite ?: existing.favorite,
      bookmark = incoming.bookmark ?: existing.bookmark,
      shuffleMode = incoming.shuffleMode ?: existing.shuffleMode,
      repeatMode = incoming.repeatMode ?: existing.repeatMode,
      playbackRate = incoming.playbackRate ?: existing.playbackRate
    )
  }
}
