package com.audiobrowser.player

import com.audiobrowser.model.PlayerSetupOptions
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the buffer-config mapping — the one decision inside engine construction. */
class PlayerEngineFactoryTest {

  @Test
  fun `maps the buffer fields and converts to Int millis`() {
    val config =
      bufferConfig(
        PlayerSetupOptions(
          minBuffer = 1000.0,
          maxBuffer = 5000.0,
          playBuffer = 1500.0,
          backBuffer = 2000.0,
          rebufferBuffer = 3000.0,
        )
      )
    assertEquals(1000, config.minBufferMs)
    assertEquals(5000, config.maxBufferMs)
    assertEquals(1500, config.bufferForPlaybackMs)
    assertEquals(3000, config.bufferForPlaybackAfterRebufferMs)
    assertEquals(2000, config.backBufferMs)
  }

  @Test
  fun `automatic rebuffer falls back to playBuffer`() {
    // rebufferBuffer unset = automatic: start post-rebuffer playback at playBuffer and let
    // AutomaticBufferManager adjust from there.
    val config = bufferConfig(PlayerSetupOptions(playBuffer = 1500.0, rebufferBuffer = null))
    assertEquals(1500, config.bufferForPlaybackAfterRebufferMs)
  }
}
