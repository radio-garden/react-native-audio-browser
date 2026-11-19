package com.audiobrowser.player

import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.TrackFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A queue the session sets, as for an Android Auto pick or playback resumption, shuffle on. */
@RunWith(RobolectricTestRunner::class)
class SessionQueueShuffleOrderTest {

  private val player = Player(RuntimeEnvironment.getApplication())
  private val items =
    (0 until 20).map {
      TrackFactory.toMedia3(TestFixtures.track(title = "$it", src = "https://s/$it.mp3"))
    }

  @After fun release() = player.exoPlayer.release()

  @Test
  fun `the start index leads the shuffle order`() {
    player.setup(PlayerSetupOptions())
    player.shuffleMode = true

    // A random order leads with the start index once in 20: ten in a row is no accident.
    repeat(10) {
      player.forwardingPlayer.setMediaItems(items, 3, 0)

      assertEquals(3, player.exoPlayer.currentMediaItemIndex)
      assertEquals(3, player.exoPlayer.currentTimeline.getFirstWindowIndex(true))
    }
  }
}
