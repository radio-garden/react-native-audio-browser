package com.doublesymmetry.trackplayer.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/** Event data for player error. */
data class PlayerErrorEvent(
  /** The error code. */
  val code: String,
  /** The error message. */
  val message: String,
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply {
      putString("code", code)
      putString("message", message)
    }
  }
}
