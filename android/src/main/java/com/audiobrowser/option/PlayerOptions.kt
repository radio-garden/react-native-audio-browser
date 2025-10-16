@file:OptIn(UnstableApi::class)

package com.audiobrowser.option

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

data class PlayerOptions(
  val cacheSizeKb: Long = 0,
  val audioContentType: AudioContentType = AudioContentType.MUSIC,
  val wakeMode: PlayerWakeMode = PlayerWakeMode.NONE,

  /**
   * Toggle whether the player should pause automatically when audio is rerouted from a headset to
   * device speakers.
   */
  val handleAudioBecomingNoisy: Boolean = true,
  var repeatMode: PlayerRepeatMode = PlayerRepeatMode.ALL,
  val bufferOptions: BufferOptions = BufferOptions(null, null, null, null, null),
  val parseEmbeddedArtwork: Boolean = false,
  val skipSilence: Boolean = false,

  /**
   * Toggle whether or not a player action triggered from an outside source should be intercepted.
   *
   * The sources can be: media buttons on headphones, Android Wear, Android Auto, Google Assistant,
   * media notification, etc.
   *
   * When set to true, external player actions are intercepted and dispatched as remote control
   * events through the TrackPlayerCallbacks interface (e.g., onRemotePlay, onRemotePause).
   */
  val interceptPlayerActionsTriggeredExternally: Boolean = false,
  val forwardJumpInterval: Double = 15.0,
  val backwardJumpInterval: Double = 15.0,
)

data class BufferOptions(
  val minBuffer: Int?,
  val maxBuffer: Int?,
  val playBuffer: Int?,
  val rebufferBuffer: Int?,
  val backBuffer: Int?,
)
