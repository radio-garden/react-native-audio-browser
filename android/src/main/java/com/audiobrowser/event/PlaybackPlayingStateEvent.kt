package com.audiobrowser.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/** Event data for playing state (playing and buffering flags). */
data class PlaybackPlayingStateEvent(
  /** Whether the player is currently playing. */
  val playing: Boolean,
  /** Whether the player is buffering during playback. */
  val buffering: Boolean,
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply {
      putBoolean("playing", playing)
      putBoolean("buffering", buffering)
    }
  }
}
