package com.audiobrowser.util

import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.PlayingState

object PlayingStateFactory {
  /**
   * `playing` reports intent, not audible output: it holds through every state but ERROR, ENDED,
   * NONE and STOPPED, PAUSED included. `buffering` implies `playing`.
   *
   * @param shouldPlayWhenReady playWhenReady and no suppression of output, not playWhenReady alone
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
