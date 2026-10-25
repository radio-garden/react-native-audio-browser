package com.audiobrowser.util

import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.PlayingState

object PlayingStateFactory {
  /**
   * Derives a PlayingState from shouldPlayWhenReady and state.
   *
   * @param shouldPlayWhenReady Whether the player wants to play when ready and nothing holds its
   *   output back
   * @param playbackState The current player state
   * @return A PlayingState representing the current playing/buffering status
   */
  fun derive(shouldPlayWhenReady: Boolean, playbackState: PlaybackState): PlayingState {
    val playing =
      shouldPlayWhenReady &&
        !(playbackState == PlaybackState.ERROR ||
          playbackState == PlaybackState.ENDED ||
          playbackState == PlaybackState.NONE ||
          playbackState == PlaybackState.STOPPED)
    val buffering =
      shouldPlayWhenReady &&
        (playbackState == PlaybackState.LOADING || playbackState == PlaybackState.BUFFERING)
    return PlayingState(playing, buffering)
  }
}
