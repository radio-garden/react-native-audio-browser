package com.doublesymmetry.trackplayer.event

import com.doublesymmetry.trackplayer.option.PlayerRepeatMode
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.margelo.nitro.audiobrowser.RepeatModeChangedEvent as NitroRepeatModeChangedEvent

data class PlaybackRepeatModeChangedEvent(
  val repeatMode: PlayerRepeatMode
) {
  fun toNitro(): NitroRepeatModeChangedEvent {
    return NitroRepeatModeChangedEvent(
      repeatMode = repeatMode.toNitro()
    )
  }
}