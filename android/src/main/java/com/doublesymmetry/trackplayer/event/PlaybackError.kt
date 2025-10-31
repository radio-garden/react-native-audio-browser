package com.doublesymmetry.trackplayer.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.margelo.nitro.audiobrowser.PlaybackError as NitroPlaybackError

data class PlaybackError(val code: String? = null, val message: String? = null) {
  fun toNitro(): NitroPlaybackError {
    return NitroPlaybackError(
      code = code ?: "",
      message = message ?: ""
    )
  }
}
