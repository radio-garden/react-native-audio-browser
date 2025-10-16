package com.audiobrowser.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/** Event data for controller disconnected. */
data class ControllerDisconnectedEvent(
  /** The package name. */
  val `package`: String
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply { putString("package", `package`) }
  }
}
