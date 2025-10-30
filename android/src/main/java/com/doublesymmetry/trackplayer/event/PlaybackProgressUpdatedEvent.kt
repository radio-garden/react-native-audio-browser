package com.doublesymmetry.trackplayer.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/** Event data for playback progress updates. */
data class PlaybackProgressUpdatedEvent(
  /** The current playback position in seconds. */
  val position: Double,
  /** The duration of the current track in seconds. */
  val duration: Double,
  /** The buffered position in seconds. */
  val buffered: Double,
  /** The index of the current track. */
  val track: Int,
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply {
      putDouble("position", position)
      putDouble("duration", duration)
      putDouble("buffered", buffered)
      putInt("track", track)
    }
  }
}
