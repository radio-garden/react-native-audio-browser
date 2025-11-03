package com.doublesymmetry.trackplayer.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.margelo.nitro.audiobrowser.PlaybackPlayWhenReadyChangedEvent as NitroPlaybackPlayWhenReadyChangedEvent

/** Event data for when playWhenReady changes. */
data class PlaybackPlayWhenReadyChangedEvent(
  /** Whether the player will play when it is ready to do so. */
  val playWhenReady: Boolean
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply { putBoolean("playWhenReady", playWhenReady) }
  }

  fun toNitro(): NitroPlaybackPlayWhenReadyChangedEvent {
    return NitroPlaybackPlayWhenReadyChangedEvent(
      playWhenReady = playWhenReady
    )
  }
}
