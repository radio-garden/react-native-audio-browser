package com.doublesymmetry.trackplayer.option

import androidx.media3.common.Player

enum class PlayerRepeatMode(val string: String) {
  OFF("off"),
  TRACK("track"),
  QUEUE("queue");

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

  companion object {
    fun fromString(value: String): PlayerRepeatMode? {
      return entries.find { it.string == value }
    }

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
  }
}
