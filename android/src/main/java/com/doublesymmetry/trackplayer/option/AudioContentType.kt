package com.doublesymmetry.trackplayer.option

import androidx.media3.common.C
import com.margelo.nitro.audiobrowser.AndroidAudioContentType

enum class AudioContentType {
  MUSIC,
  SPEECH,
  SONIFICATION,
  MOVIE,
  UNKNOWN;

  fun toMedia3(): Int {
    return when (this) {
      MUSIC -> C.AUDIO_CONTENT_TYPE_MUSIC
      SPEECH -> C.AUDIO_CONTENT_TYPE_SPEECH
      SONIFICATION -> C.AUDIO_CONTENT_TYPE_SONIFICATION
      MOVIE -> C.AUDIO_CONTENT_TYPE_MOVIE
      UNKNOWN -> C.AUDIO_CONTENT_TYPE_UNKNOWN
    }
  }

  fun toNitro(): AndroidAudioContentType {
    return when (this) {
      MUSIC -> AndroidAudioContentType.MUSIC
      SPEECH -> AndroidAudioContentType.SPEECH
      SONIFICATION -> AndroidAudioContentType.SONIFICATION
      MOVIE -> AndroidAudioContentType.MOVIE
      UNKNOWN -> AndroidAudioContentType.UNKNOWN
    }
  }

  companion object {
    fun fromNitro(value: AndroidAudioContentType): AudioContentType {
      return when (value) {
        AndroidAudioContentType.MUSIC -> MUSIC
        AndroidAudioContentType.SPEECH -> SPEECH
        AndroidAudioContentType.SONIFICATION -> SONIFICATION
        AndroidAudioContentType.MOVIE -> MOVIE
        AndroidAudioContentType.UNKNOWN -> UNKNOWN
      }
    }
  }
}
