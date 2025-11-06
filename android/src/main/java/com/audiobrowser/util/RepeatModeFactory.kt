package com.audiobrowser.util

import androidx.media3.common.Player
import com.margelo.nitro.audiobrowser.RepeatMode

object RepeatModeFactory {
  fun toMedia3(repeatMode: RepeatMode): Int {
    return when (repeatMode) {
      RepeatMode.OFF -> Player.REPEAT_MODE_OFF
      RepeatMode.TRACK -> Player.REPEAT_MODE_ONE
      RepeatMode.QUEUE -> Player.REPEAT_MODE_ALL
    }
  }

  fun fromMedia3(repeatMode: Int): RepeatMode {
    return when (repeatMode) {
      Player.REPEAT_MODE_ALL -> RepeatMode.QUEUE
      Player.REPEAT_MODE_ONE -> RepeatMode.TRACK
      else -> RepeatMode.OFF
    }
  }
}
