package com.audiobrowser.option

enum class PlayerCapability(val string: String) {
  PLAY("play"),
  PLAY_FROM_ID("play-from-id"),
  PLAY_FROM_SEARCH("play-from-search"),
  PAUSE("pause"),
  STOP("stop"),
  SEEK_TO("seek-to"),
  SKIP("skip"),
  SKIP_TO_NEXT("skip-to-next"),
  SKIP_TO_PREVIOUS("skip-to-previous"),
  JUMP_FORWARD("jump-forward"),
  JUMP_BACKWARD("jump-backward"),
  SET_RATING("set-rating"),
  LIKE("like"),
  DISLIKE("dislike"),
  BOOKMARK("bookmark");

  companion object {
    fun fromString(value: String): PlayerCapability? {
      return entries.find { it.string == value }
    }
  }
}
