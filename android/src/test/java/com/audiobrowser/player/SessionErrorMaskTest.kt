package com.audiobrowser.player

import androidx.media3.common.Player as MediaPlayer
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionErrorMaskTest {

  @Test
  fun `idle with an error masks to ready while keep-alive is on`() {
    assertEquals(
      MediaPlayer.STATE_READY,
      SessionErrorMask.playbackState(MediaPlayer.STATE_IDLE, hasError = true, keepAlive = true),
    )
    assertEquals(
      false,
      SessionErrorMask.playWhenReady(
        raw = true,
        state = MediaPlayer.STATE_IDLE,
        hasError = true,
        keepAlive = true,
      ),
    )
  }

  @Test
  fun `no masking without keep-alive`() {
    assertEquals(
      MediaPlayer.STATE_IDLE,
      SessionErrorMask.playbackState(MediaPlayer.STATE_IDLE, hasError = true, keepAlive = false),
    )
    assertEquals(
      true,
      SessionErrorMask.playWhenReady(
        raw = true,
        state = MediaPlayer.STATE_IDLE,
        hasError = true,
        keepAlive = false,
      ),
    )
  }

  @Test
  fun `no masking without an error or outside idle`() {
    assertEquals(
      MediaPlayer.STATE_IDLE,
      SessionErrorMask.playbackState(MediaPlayer.STATE_IDLE, hasError = false, keepAlive = true),
    )
    assertEquals(
      MediaPlayer.STATE_BUFFERING,
      SessionErrorMask.playbackState(MediaPlayer.STATE_BUFFERING, hasError = true, keepAlive = true),
    )
    assertEquals(
      true,
      SessionErrorMask.playWhenReady(
        raw = true,
        state = MediaPlayer.STATE_READY,
        hasError = true,
        keepAlive = true,
      ),
    )
  }
}
