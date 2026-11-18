package com.audiobrowser.player

import com.audiobrowser.model.PlayerSetupOptions
import com.margelo.nitro.audiobrowser.RepeatMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PendingPlayerStateTest {

  private val players = mutableListOf<Player>()

  private fun player() =
    Player(RuntimeEnvironment.getApplication()).also {
      it.setup(PlayerSetupOptions())
      players += it
    }

  @After fun release() = players.forEach { it.exoPlayer.release() }

  @Test
  fun `applies shuffle held before the player existed`() {
    val player = player()
    val pending = PendingPlayerState().apply { shuffleEnabled = true }

    pending.applyTo(player)

    assertTrue(player.shuffleMode)
  }

  @Test
  fun `applies repeat mode and play-when-ready`() {
    val player = player()
    val pending =
      PendingPlayerState().apply {
        repeatMode = RepeatMode.QUEUE
        playWhenReady = true
      }

    pending.applyTo(player)

    assertEquals(RepeatMode.QUEUE, player.repeatMode)
    assertTrue(player.playWhenReady)
  }

  @Test
  fun `applying consumes the state`() {
    val pending =
      PendingPlayerState().apply {
        shuffleEnabled = true
        repeatMode = RepeatMode.TRACK
        playWhenReady = true
      }

    pending.applyTo(player())
    val later = player()
    pending.applyTo(later)

    assertFalse(later.shuffleMode)
    assertEquals(RepeatMode.OFF, later.repeatMode)
    assertFalse(later.playWhenReady)
  }
}
