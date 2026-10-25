package com.audiobrowser.player

import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.PlaybackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// Robolectric: Player builds a real ExoPlayer, which needs the framework and a main looper.
@RunWith(RobolectricTestRunner::class)
class PlayerSetupTest {

  private val player = Player(RuntimeEnvironment.getApplication())

  @After fun release() = player.exoPlayer.release()

  /** Service.onCreate and AudioBrowser.onServiceConnected set up with the same options. */
  @Test
  fun setupWithTheSameOptionsKeepsTheEngine() {
    player.setup(PlayerSetupOptions())
    val engine = player.exoPlayer

    player.setup(PlayerSetupOptions())
    player.setup(PlayerSetupOptions(autoUpdateNowPlayingMetadata = false))

    assertSame(engine, player.exoPlayer)
  }

  @Test
  fun setupWithChangedOptionsRebuildsTheEngineAndKeepsPlaying() {
    player.setup(PlayerSetupOptions())
    val engine = player.exoPlayer
    engine.setMediaItems(listOf(item("a"), item("b"), item("c")), 1, 5_000)
    engine.repeatMode = MediaPlayer.REPEAT_MODE_ALL
    engine.shuffleModeEnabled = true
    engine.volume = 0.5f
    engine.prepare()
    engine.playWhenReady = true
    player.setPlaybackState(PlaybackState.PLAYING)
    val callbacks = mock(Callbacks::class.java)
    player.setCallbacks(callbacks)

    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))

    val rebuilt = player.exoPlayer
    assertNotSame(engine, rebuilt)
    assertEquals(listOf("a", "b", "c"), player.tracks.map { it.title })
    assertEquals(1, rebuilt.currentMediaItemIndex)
    assertEquals(5_000, rebuilt.currentPosition)
    assertEquals(MediaPlayer.REPEAT_MODE_ALL, rebuilt.repeatMode)
    assertTrue(rebuilt.shuffleModeEnabled)
    assertEquals(0.5f, rebuilt.volume)
    assertTrue(rebuilt.playbackState != MediaPlayer.STATE_IDLE)
    assertTrue(rebuilt.playWhenReady)
    assertTrue(player.playWhenReadyCache)
    assertEquals(PlaybackState.BUFFERING, player.playbackState)
    assertEquals(
      emptyList<String>(),
      mockingDetails(callbacks)
        .invocations
        .map { it.method.name }
        .filter { it == "onPlaybackActiveTrackChanged" },
    )
  }

  @Test
  fun setupWithChangedOptionsKeepsAnUnpreparedQueueStopped() {
    player.setup(PlayerSetupOptions())
    player.exoPlayer.setMediaItems(listOf(item("a")))

    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))

    assertEquals(listOf("a"), player.tracks.map { it.title })
    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
    assertEquals(PlaybackState.NONE, player.playbackState)
  }

  private fun item(title: String) =
    TrackFactory.toMedia3(TestFixtures.track(title = title, src = "https://s/$title.mp3"))
}
