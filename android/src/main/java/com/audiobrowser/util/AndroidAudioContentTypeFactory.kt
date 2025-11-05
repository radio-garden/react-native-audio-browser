package com.audiobrowser.util

import androidx.media3.common.C
import com.margelo.nitro.audiobrowser.AndroidAudioContentType

object AndroidAudioContentTypeFactory {

    fun toMedia3(value: AndroidAudioContentType): Int {
        return when (value) {
            AndroidAudioContentType.MUSIC -> C.AUDIO_CONTENT_TYPE_MUSIC
            AndroidAudioContentType.SPEECH -> C.AUDIO_CONTENT_TYPE_SPEECH
            AndroidAudioContentType.SONIFICATION -> C.AUDIO_CONTENT_TYPE_SONIFICATION
            AndroidAudioContentType.MOVIE -> C.AUDIO_CONTENT_TYPE_MOVIE
            AndroidAudioContentType.UNKNOWN -> C.AUDIO_CONTENT_TYPE_UNKNOWN
        }
    }
}