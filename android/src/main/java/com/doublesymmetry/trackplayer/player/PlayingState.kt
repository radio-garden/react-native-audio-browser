package com.doublesymmetry.trackplayer.player

import com.doublesymmetry.trackplayer.event.PlaybackPlayingStateEvent
import com.doublesymmetry.trackplayer.model.State
import com.margelo.nitro.audiobrowser.PlayingState

/** Manages the playing state (playing and buffering flags) and notifies when they change. */
class PlayingState(private val onChange: (PlaybackPlayingStateEvent) -> Unit) {
  var playing: Boolean = false
    private set

  var buffering: Boolean = false
    private set

  fun update(playWhenReady: Boolean, state: State) {
    val newPlaying =
      playWhenReady && !(state == State.ERROR || state == State.ENDED || state == State.NONE)
    val newBuffering = playWhenReady && (state == State.LOADING || state == State.BUFFERING)

    if (newPlaying != playing || newBuffering != buffering) {
      playing = newPlaying
      buffering = newBuffering
      onChange(PlaybackPlayingStateEvent(playing, buffering))
    }
  }

  fun toNitro(): PlayingState {
    return PlayingState(playing, buffering)
  }
}
