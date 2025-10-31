package com.doublesymmetry.trackplayer.model

import com.doublesymmetry.trackplayer.event.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackState as NitroPlaybackState
import com.margelo.nitro.audiobrowser.State as NitroState

/**
 * Represents the current state of the audio player. Includes the playback state and any associated
 * error information.
 */
data class PlaybackState(val state: State, val error: PlaybackError? = null) {
  fun toNitro(): NitroPlaybackState = NitroPlaybackState(
    state = when (state) {
      State.NONE -> NitroState.NONE
      State.READY -> NitroState.READY
      State.PLAYING -> NitroState.PLAYING
      State.PAUSED -> NitroState.PAUSED
      State.STOPPED -> NitroState.STOPPED
      State.LOADING -> NitroState.LOADING
      State.BUFFERING -> NitroState.BUFFERING
      State.ERROR -> NitroState.ERROR
      State.ENDED -> NitroState.ENDED
    },
    error = error?.toNitro()
  )
}
