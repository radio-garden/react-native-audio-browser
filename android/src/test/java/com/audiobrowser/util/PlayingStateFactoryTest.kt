package com.audiobrowser.util

import com.margelo.nitro.audiobrowser.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth table for the PlayingState derivation — the single transition function behind
 * `onPlaybackPlayingState` (see Player.refreshPlayingState, its only production caller path).
 */
class PlayingStateFactoryTest {

  @Test
  fun `playWhenReady false is never playing nor buffering`() {
    for (state in PlaybackState.entries) {
      val derived = PlayingStateFactory.derive(playWhenReady = false, playbackState = state)
      assertEquals("playing for $state", false, derived.playing)
      assertEquals("buffering for $state", false, derived.buffering)
    }
  }

  @Test
  fun `playWhenReady true plays except in terminal or empty states`() {
    val notPlaying = setOf(PlaybackState.ERROR, PlaybackState.ENDED, PlaybackState.NONE)
    for (state in PlaybackState.entries) {
      val derived = PlayingStateFactory.derive(playWhenReady = true, playbackState = state)
      assertEquals("playing for $state", state !in notPlaying, derived.playing)
    }
  }

  @Test
  fun `buffering only while loading or rebuffering with playWhenReady`() {
    val buffering = setOf(PlaybackState.LOADING, PlaybackState.BUFFERING)
    for (state in PlaybackState.entries) {
      val derived = PlayingStateFactory.derive(playWhenReady = true, playbackState = state)
      assertEquals("buffering for $state", state in buffering, derived.buffering)
    }
  }
}
