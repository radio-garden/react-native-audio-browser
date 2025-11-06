package com.audiobrowser.util

import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.PlayingState

object PlayingStateFactory {
  /**
   * Derives a PlayingState from playWhenReady and state.
   *
   * @param playWhenReady Whether the player wants to play when ready
   * @param playbackState The current player state
   * @return A PlayingState representing the current playing/buffering status
   */
  fun derive(playWhenReady: Boolean, playbackState: PlaybackState): PlayingState {
    val playing =
      playWhenReady &&
        !(playbackState == PlaybackState.ERROR ||
          playbackState == PlaybackState.ENDED ||
          playbackState == PlaybackState.NONE)
    val buffering =
      playWhenReady &&
        (playbackState == PlaybackState.LOADING || playbackState == PlaybackState.BUFFERING)
    return PlayingState(playing, buffering)
  }
}
