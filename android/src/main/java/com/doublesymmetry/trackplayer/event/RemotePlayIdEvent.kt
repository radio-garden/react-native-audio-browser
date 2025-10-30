package com.doublesymmetry.trackplayer.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/** Event data for remote play id command. */
data class RemotePlayIdEvent(
  /** The track id. */
  val id: String
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply { putString("id", id) }
  }
}
