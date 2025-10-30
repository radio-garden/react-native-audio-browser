package com.doublesymmetry.trackplayer.event

import com.doublesymmetry.trackplayer.option.PlayerRepeatMode
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

data class PlaybackRepeatModeChangedEvent(
  val repeatMode: PlayerRepeatMode
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply {
      putString("repeatMode", repeatMode.string)
    }
  }
}