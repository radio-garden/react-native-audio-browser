package com.doublesymmetry.trackplayer.option

import androidx.media3.common.Player
import com.margelo.nitro.audiobrowser.RepeatMode as NitroRepeatMode

enum class PlayerRepeatMode {
  OFF,
  TRACK,
  QUEUE;

  /**
   * Converts this PlayerRepeatMode to the corresponding Media3 Player repeat mode
   */
  fun toMedia3(): Int {
    return when (this) {
      OFF -> Player.REPEAT_MODE_OFF
      TRACK -> Player.REPEAT_MODE_ONE
      QUEUE -> Player.REPEAT_MODE_ALL
    }
  }

  fun toNitro(): NitroRepeatMode = when (this) {
    OFF -> NitroRepeatMode.OFF
    TRACK -> NitroRepeatMode.TRACK
    QUEUE -> NitroRepeatMode.QUEUE
  }

  companion object {

    /**
     * Converts a Media3 Player repeat mode to PlayerRepeatMode
     */
    fun fromMedia3(repeatMode: Int): PlayerRepeatMode {
      return when (repeatMode) {
        Player.REPEAT_MODE_ALL -> QUEUE
        Player.REPEAT_MODE_ONE -> TRACK
        else -> OFF
      }
    }

    fun fromNitro(nitroMode: NitroRepeatMode): PlayerRepeatMode = when (nitroMode) {
      NitroRepeatMode.OFF -> OFF
      NitroRepeatMode.TRACK -> TRACK
      NitroRepeatMode.QUEUE -> QUEUE
    }
  }
}
