package com.audiobrowser.player

import androidx.media3.common.Player as MediaPlayer
import com.margelo.nitro.audiobrowser.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionViewRulesTest {

  private fun view(
    engineState: Int = MediaPlayer.STATE_IDLE,
    state: PlaybackState = PlaybackState.NONE,
    engineFailed: Boolean = false,
    keepAlive: Boolean = true,
  ) = SessionView.playbackState(engineState, state, engineFailed, keepAlive)

  @Test
  fun `an idle engine with an error, in ERROR or in PAUSED reads ready while keep-alive is on`() {
    assertEquals(MediaPlayer.STATE_READY, view(state = PlaybackState.PLAYING, engineFailed = true))
    assertEquals(MediaPlayer.STATE_READY, view(state = PlaybackState.ERROR))
    assertEquals(MediaPlayer.STATE_READY, view(state = PlaybackState.PAUSED))
  }

  @Test
  fun `an ended queue on an idle engine reads ended, keep-alive or not`() {
    assertEquals(MediaPlayer.STATE_ENDED, view(state = PlaybackState.ENDED))
    assertEquals(MediaPlayer.STATE_ENDED, view(state = PlaybackState.ENDED, keepAlive = false))
  }

  @Test
  fun `no masking without keep-alive`() {
    assertEquals(MediaPlayer.STATE_IDLE, view(engineFailed = true, keepAlive = false))
    assertEquals(MediaPlayer.STATE_IDLE, view(state = PlaybackState.ERROR, keepAlive = false))
  }

  @Test
  fun `no masking in other states or outside idle`() {
    assertEquals(MediaPlayer.STATE_IDLE, view(state = PlaybackState.STOPPED))
    assertEquals(MediaPlayer.STATE_IDLE, view(state = PlaybackState.STOPPED, engineFailed = true))
    assertEquals(MediaPlayer.STATE_IDLE, view(state = PlaybackState.NONE))
    assertEquals(
      MediaPlayer.STATE_BUFFERING,
      view(engineState = MediaPlayer.STATE_BUFFERING, engineFailed = true),
    )
  }

  @Test
  fun `an idle engine read as anything else isn't about to play`() {
    assertEquals(
      false,
      SessionView.playWhenReady(true, MediaPlayer.STATE_IDLE, MediaPlayer.STATE_READY),
    )
    assertEquals(
      true,
      SessionView.playWhenReady(true, MediaPlayer.STATE_IDLE, MediaPlayer.STATE_IDLE),
    )
    assertEquals(
      true,
      SessionView.playWhenReady(true, MediaPlayer.STATE_READY, MediaPlayer.STATE_READY),
    )
  }
}
