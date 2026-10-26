package com.audiobrowser.player

import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// Robolectric: Player builds a real ExoPlayer, which needs the framework and a main looper.
@RunWith(RobolectricTestRunner::class)
class PlayerInvalidTrackTest {

  private val player = Player(RuntimeEnvironment.getApplication())
  private val invalid = TestFixtures.track(title = "invalid", src = null)

  @Before fun setup() = player.setup(PlayerSetupOptions())

  @After fun release() = player.exoPlayer.release()

  @Test
  fun loadIntoAnEmptyQueueThrows() {
    assertThrows(IllegalArgumentException::class.java) { player.load(invalid) }

    assertEquals(emptyList<String>(), titles())
  }

  @Test
  fun loadOverTheCurrentTrackThrows() {
    player.setQueue(arrayOf(valid("a"), valid("b")), startIndex = 1)

    assertThrows(IllegalArgumentException::class.java) { player.load(invalid) }

    assertEquals(listOf("a", "b"), titles())
    assertEquals(1, player.exoPlayer.currentMediaItemIndex)
  }

  @Test
  fun addThrowsAndAddsNoneOfTheTracks() {
    player.setQueue(arrayOf(valid("a")))

    assertThrows(IllegalArgumentException::class.java) {
      player.add(arrayOf(valid("b"), invalid), -1)
    }

    assertEquals(listOf("a"), titles())
  }

  @Test
  fun setQueueThrowsAndKeepsTheQueue() {
    player.setQueue(arrayOf(valid("a")))

    assertThrows(IllegalArgumentException::class.java) {
      player.setQueue(arrayOf(valid("b"), invalid))
    }

    assertEquals(listOf("a"), titles())
  }

  private fun valid(title: String) = TestFixtures.track(title = title, src = "https://s/$title.mp3")

  private fun titles() = player.tracks.map { it.title }
}
