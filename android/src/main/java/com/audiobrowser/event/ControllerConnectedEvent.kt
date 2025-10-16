package com.audiobrowser.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/** Event data for controller connected. */
data class ControllerConnectedEvent(
  /** The package name. */
  val `package`: String,
  /** Whether this is a media notification controller. */
  val isMediaNotificationController: Boolean,
  /** Whether this is an automotive controller. */
  val isAutomotiveController: Boolean,
  /** Whether this is an auto companion controller. */
  val isAutoCompanionController: Boolean,
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply {
      putString("package", `package`)
      putBoolean("isMediaNotificationController", isMediaNotificationController)
      putBoolean("isAutomotiveController", isAutomotiveController)
      putBoolean("isAutoCompanionController", isAutoCompanionController)
    }
  }
}
