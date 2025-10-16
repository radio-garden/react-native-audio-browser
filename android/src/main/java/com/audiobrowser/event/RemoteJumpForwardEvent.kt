package com.audiobrowser.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/** Event data for remote jump forward command. */
data class RemoteJumpForwardEvent(
  /** The number of seconds to jump forward. */
  val interval: Double
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply { putDouble("interval", interval) }
  }
}
