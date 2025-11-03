package com.doublesymmetry.trackplayer.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.margelo.nitro.audiobrowser.PlaybackQueueEndedEvent as NitroPlaybackQueueEndedEvent

/** Event data for when the playback queue has ended. */
data class PlaybackQueueEndedEvent(
  /** The index of the active track when the playback queue ended. */
  val track: Int,
  /** The playback position in seconds of the active track when the playback queue ended. */
  val position: Double,
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply {
      putInt("track", track)
      putDouble("position", position)
    }
  }

  fun toNitro(): NitroPlaybackQueueEndedEvent {
    return NitroPlaybackQueueEndedEvent(
      track = track.toDouble(),
      position = position
    )
  }
}
