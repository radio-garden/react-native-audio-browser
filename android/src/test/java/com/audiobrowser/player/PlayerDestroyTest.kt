package com.audiobrowser.player

import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class PlayerDestroyTest {

  private val player = Player(RuntimeEnvironment.getApplication())

  @Before
  fun load() {
    player.setup(PlayerSetupOptions())
    player.sessionPlayer.setMediaItems(
      listOf(TrackFactory.toMedia3(TestFixtures.track(title = "a", src = "https://s/a.mp3"))),
      0,
      5_000,
    )
    player.sessionPlayer.prepare()
  }

  @Test
  fun destroySavesASeekMadeWhileNotPlaying() {
    player.sessionPlayer.seekTo(8_000)

    player.destroy()

    assertEquals(8_000L, player.playbackStateStore.get()?.positionMs)
  }

  @Test
  fun destroyAfterTheQueueEndedKeepsPositionZero() {
    player.playWhenReady = true
    player.setPlaybackState(PlaybackState.ENDED)

    player.destroy()

    assertEquals(0L, player.playbackStateStore.get()?.positionMs)
  }

  @Test
  fun destroyTwiceReleasesTheEngineOnce() {
    player.destroy()
    player.destroy()

    assertEquals(
      1,
      ShadowLog.getLogsForTag("ExoPlayerImpl").count { it.msg.startsWith("Release ") },
    )
  }
}
