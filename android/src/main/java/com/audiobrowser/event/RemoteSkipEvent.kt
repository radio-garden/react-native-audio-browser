package com.audiobrowser.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/** Event data for remote skip command. */
data class RemoteSkipEvent(
  /** The index to skip to. */
  val index: Int
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply { putInt("index", index) }
  }
}
