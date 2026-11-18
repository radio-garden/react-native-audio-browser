package com.audiobrowser.player

import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.TrackFactory
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlayerSleepTimerTest {

  private val player = Player(RuntimeEnvironment.getApplication())

  @After fun release() = player.exoPlayer.release()

  @Test
  fun `the end-of-track sleep pauses a repeating track when it loops`() {
    player.setup(PlayerSetupOptions())
    val item = TrackFactory.toMedia3(TestFixtures.track())
    player.sessionPlayer.setMediaItems(listOf(item))
    player.playWhenReady = true
    player.sleepTimer.setEndOfTrack()

    player.playerListener.onMediaItemTransition(
      item,
      MediaPlayer.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
    )

    assertFalse(player.playWhenReady)
    assertTrue(player.sleepTimer.get().isFirst)
  }
}
