package com.doublesymmetry.trackplayer.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.margelo.nitro.audiobrowser.RemoteSeekEvent as NitroRemoteSeekEvent

/** Event data for remote seek command. */
data class RemoteSeekEvent(
  /** The position to seek to in seconds. */
  val position: Double
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply { putDouble("position", position) }
  }

  fun toNitro(): NitroRemoteSeekEvent {
    return NitroRemoteSeekEvent(position = position)
  }
}
