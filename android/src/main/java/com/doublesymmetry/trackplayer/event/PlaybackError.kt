package com.doublesymmetry.trackplayer.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

data class PlaybackError(val code: String? = null, val message: String? = null) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply {
      message?.let { putString("message", it) }
      code?.let { putString("code", "android-$it") }
    }
  }
}
