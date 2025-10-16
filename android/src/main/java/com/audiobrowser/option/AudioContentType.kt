@file:OptIn(UnstableApi::class)

package com.audiobrowser.option

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi

enum class AudioContentType {
  MUSIC,
  SPEECH,
  SONIFICATION,
  MOVIE,
  UNKNOWN;

  fun toExoPlayer(): Int {
    return when (this) {
      MUSIC -> C.AUDIO_CONTENT_TYPE_MUSIC
      SPEECH -> C.AUDIO_CONTENT_TYPE_SPEECH
      SONIFICATION -> C.AUDIO_CONTENT_TYPE_SONIFICATION
      MOVIE -> C.AUDIO_CONTENT_TYPE_MOVIE
      UNKNOWN -> C.AUDIO_CONTENT_TYPE_UNKNOWN
    }
  }

  companion object {
    fun fromString(value: String): AudioContentType {
      return when (value.lowercase()) {
        "music" -> MUSIC
        "speech" -> SPEECH
        "sonification" -> SONIFICATION
        "movie" -> MOVIE
        "unknown" -> UNKNOWN
        else -> throw IllegalArgumentException("Unknown audio content type: $value")
      }
    }
  }
}
