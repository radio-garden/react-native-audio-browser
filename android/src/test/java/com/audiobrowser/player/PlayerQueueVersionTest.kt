package com.audiobrowser.player

import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.TrackFactory
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlayerQueueVersionTest {

  private val player = Player(RuntimeEnvironment.getApplication())

  private fun tracks(vararg titles: String) =
    titles.map { TestFixtures.track(title = it, src = "https://s/$it.mp3") }.toTypedArray()

  @Before fun setUp() = player.setup(PlayerSetupOptions())

  @After fun release() = player.exoPlayer.release()

  private fun changes(mutate: () -> Unit): Boolean {
    player.setQueue(tracks("a", "b", "c"))
    val before = player.queueVersion
    mutate()
    return player.queueVersion != before
  }

  @Test
  fun `every change to the queue changes it before the call returns`() {
    assertTrue(changes { player.setQueue(tracks("c", "b", "a")) })
    assertTrue(changes { player.add(tracks("d")) })
    assertTrue(changes { player.remove(listOf(1)) })
    assertTrue(changes { player.move(0, 2) })
    assertTrue(
      changes { player.replaceTrack(0, TestFixtures.track(title = "x", src = "https://s/x.mp3")) }
    )
    assertTrue(changes { player.removeUpcomingTracks() })
  }

  @Test
  fun `a queue the session sets, as a car pick does, changes it`() {
    assertTrue(
      changes {
        player.sessionPlayer.setMediaItems(TrackFactory.toMedia3(tracks("c", "a")).toList())
      }
    )
  }

  @Test
  fun `the same tracks set again leave it`() {
    assertFalse(changes { player.setQueue(tracks("a", "b", "c")) })
  }

  @Test
  fun `navigation leaves it`() {
    assertFalse(changes { player.skipTo(2) })
    assertFalse(changes { player.previous() })
  }

  @Test
  fun `it never repeats across players`() {
    val second = Player(RuntimeEnvironment.getApplication())
    try {
      player.setQueue(tracks("a", "b", "c"))
      second.setup(PlayerSetupOptions())
      second.setQueue(tracks("a", "b", "c"))
      assertNotEquals(player.queueVersion, second.queueVersion)
    } finally {
      second.exoPlayer.release()
    }
  }
}
