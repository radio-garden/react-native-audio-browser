package com.audiobrowser.option

enum class PlayerRepeatMode(val string: String) {
  OFF("off"),
  ONE("track"),
  ALL("queue");

  companion object {
    fun fromString(value: String): PlayerRepeatMode? {
      return entries.find { it.string == value }
    }
  }
}
