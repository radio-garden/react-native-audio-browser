package com.audiobrowser.model

import com.margelo.nitro.audiobrowser.AndroidOptions
import com.margelo.nitro.audiobrowser.AppKilledPlaybackBehavior
import com.margelo.nitro.audiobrowser.NativeUpdateOptions
import com.margelo.nitro.audiobrowser.Options
import com.margelo.nitro.audiobrowser.PlayerCapabilities
import com.margelo.nitro.audiobrowser.RemoteButtonLayout
import com.margelo.nitro.audiobrowser.Variant_NullType_Double
import com.margelo.nitro.audiobrowser.Variant_NullType_RemoteButtonLayout

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
  // Exceptions: jumpForward, jumpBackward default to false
  var capabilities: PlayerCapabilities =
    PlayerCapabilities(
      play = null,
      pause = null,
      stop = null,
      seekTo = null,
      skipToNext = null,
      skipToPrevious = null,
      jumpForward = false,
      jumpBackward = false,
      favorite = null,
      shuffleMode = null,
      repeatMode = null,
      playbackRate = null,
    ),

  // Ordered button layout (null = derive from capabilities)
  var remoteButtonLayout: RemoteButtonLayout? = null,

  // Android-specific runtime options (all under android.* in JS)
  var appKilledPlaybackBehavior: AppKilledPlaybackBehavior =
    AppKilledPlaybackBehavior.STOP_PLAYBACK_AND_REMOVE_NOTIFICATION,
  var skipSilence: Boolean = false,
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

    options.capabilities?.let { newCaps -> capabilities = mergeCapabilities(capabilities, newCaps) }

    // Update Android-specific options
    options.android?.let { androidOptions ->
      androidOptions.appKilledPlaybackBehavior?.let { appKilledPlaybackBehavior = it }

      // Update boolean options
      androidOptions.skipSilence?.let { skipSilence = it }

      // Handle remoteButtonLayout - variant allows distinguishing undefined from null
      androidOptions.remoteButtonLayout?.let { variant ->
        remoteButtonLayout =
          when (variant) {
            is Variant_NullType_RemoteButtonLayout.First -> null
            is Variant_NullType_RemoteButtonLayout.Second -> variant.value
          }
      }
    }
  }

  /** The resolved options in their wire shape (what getOptions/onOptionsChanged report). */
  fun toNitro(): Options {
    return Options(
      android =
        AndroidOptions(
          appKilledPlaybackBehavior = appKilledPlaybackBehavior,
          skipSilence = skipSilence,
          remoteButtonLayout =
            remoteButtonLayout?.let { Variant_NullType_RemoteButtonLayout.create(it) },
        ),
      forwardJumpInterval = forwardJumpInterval,
      backwardJumpInterval = backwardJumpInterval,
      progressUpdateEventInterval =
        progressUpdateEventInterval?.let { Variant_NullType_Double.create(it) },
      capabilities = capabilities,
      ios = null, // iOS-only options
    )
  }

  /** Merge incoming capabilities with existing - only explicitly set values override */
  private fun mergeCapabilities(
    existing: PlayerCapabilities,
    incoming: PlayerCapabilities,
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
      shuffleMode = incoming.shuffleMode ?: existing.shuffleMode,
      repeatMode = incoming.repeatMode ?: existing.repeatMode,
      playbackRate = incoming.playbackRate ?: existing.playbackRate,
    )
  }
}
