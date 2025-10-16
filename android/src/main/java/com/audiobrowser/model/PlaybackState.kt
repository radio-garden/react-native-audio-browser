package com.audiobrowser.model

import com.audiobrowser.event.PlaybackError
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/**
 * Represents the current state of the audio player. Includes the playback state and any associated
 * error information.
 */
data class PlaybackState(val state: State, val error: PlaybackError? = null) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply {
      putString("state", state.bridge)
      error?.let { putMap("error", it.toBridge()) }
    }
  }
}
