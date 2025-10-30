package com.doublesymmetry.trackplayer.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/** Event data for when playWhenReady changes. */
data class PlaybackPlayWhenReadyChangedEvent(
  /** Whether the player will play when it is ready to do so. */
  val playWhenReady: Boolean
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply { putBoolean("playWhenReady", playWhenReady) }
  }
}
