package com.doublesymmetry.trackplayer.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.margelo.nitro.audiobrowser.RemoteJumpBackwardEvent as NitroRemoteJumpBackwardEvent

/** Event data for remote jump backward command. */
data class RemoteJumpBackwardEvent(
  /** The number of seconds to jump backward. */
  val interval: Double
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply { putDouble("interval", interval) }
  }

  fun toNitro(): NitroRemoteJumpBackwardEvent {
    return NitroRemoteJumpBackwardEvent(interval = interval)
  }
}
