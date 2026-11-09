package com.audiobrowser.player

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.PlaybackPlayWhenReadyChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.PlayingState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PlayerSetupTest {

  private val player = Player(RuntimeEnvironment.getApplication())

  @After fun release() = player.exoPlayer.release()

  @Test
  fun setupWithTheSameOptionsKeepsTheEngine() {
    player.setup(PlayerSetupOptions())
    val engine = player.exoPlayer

    // The service and the module each set up with the same options.
    player.setup(PlayerSetupOptions())
    // An option the engine is not built from.
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
    // The new engine buffers again from the kept position.
    assertEquals(PlaybackState.BUFFERING, player.playbackState)
    assertEquals(
      emptyList<String>(),
      mockingDetails(callbacks)
        .invocations
        .map { it.method.name }
        .filter { it == "onPlaybackActiveTrackChanged" },
    )
  }

  // SDK 28: from 33, media3 asks AudioManager.getAudioDevicesForAttributes, which Robolectric 4.11
  // answers with null.
  @Test
  @Config(sdk = [28])
  fun setupWithChangedOptionsReportsAnIntentTheNewEngineRefuses() {
    player.setup(PlayerSetupOptions())
    val engine = player.exoPlayer
    engine.setMediaItems(listOf(item("a")))
    engine.prepare()
    engine.playWhenReady = true
    player.setPlaybackState(PlaybackState.BUFFERING)
    assertEquals(PlayingState(true, true), player.getPlayingState())
    val callbacks = mock(Callbacks::class.java)
    player.setCallbacks(callbacks)
    val audioManager =
      shadowOf(
        RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE) as AudioManager
      )
    audioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))
    // ExoPlayer requests focus on its playback thread and posts the refusal back.
    assertTrue(player.exoPlayer.playWhenReady)
    val deadline = System.currentTimeMillis() + 5_000
    while (player.exoPlayer.playWhenReady && System.currentTimeMillis() < deadline) {
      Thread.sleep(10)
      shadowOf(Looper.getMainLooper()).idle()
    }

    assertFalse("no focus refusal within 5 s", player.exoPlayer.playWhenReady)
    assertEquals(PlaybackState.PAUSED, player.playbackState)
    assertEquals(PlayingState(false, false), player.getPlayingState())
    verify(callbacks).onPlaybackPlayWhenReadyChanged(PlaybackPlayWhenReadyChangedEvent(false))
    verify(callbacks).onPlaybackPlayingState(PlayingState(false, false))
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
