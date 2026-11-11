package com.audiobrowser.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.util.Util
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.goOnline
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.RetryPolicy
import com.audiobrowser.silentWavUri
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.PlaybackState
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/** A play from the session, or JS's setPlayWhenReady(true), plays as play() does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionPlayTest {

  @get:Rule val folder = TemporaryFolder()

  private val context = RuntimeEnvironment.getApplication()
  private val player = Player(context)
  private val callbacks = mock(Callbacks::class.java)

  @After
  fun release() {
    player.networkMonitor.destroy()
    player.exoPlayer.release()
  }

  @Test
  fun `an app's play handler takes a session play once and nothing plays`() {
    load(folder.silentWavUri(Duration.ofMillis(500)))
    player.stop()
    `when`(callbacks.handleRemotePlay()).thenReturn(true)

    // What media3 runs for a controller's play.
    Util.handlePlayButtonAction(player.forwardingPlayer)
    idleFor(1)

    verify(callbacks, times(1)).handleRemotePlay()
    assertFalse(player.exoPlayer.playWhenReady)
    assertFalse(player.isPlaying)
  }

  @Test
  fun `a session play after a stop loads once and plays`() {
    load(folder.silentWavUri(Duration.ofMillis(500)))
    player.stop()
    val before = states().size

    Util.handlePlayButtonAction(player.forwardingPlayer)
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }

    assertEquals(listOf(PlaybackState.BUFFERING, PlaybackState.PLAYING), states().drop(before))
  }

  @Test
  fun `setPlayWhenReady(true) after a stop loads`() {
    load(folder.silentWavUri(Duration.ofMillis(500)))
    player.stop()

    // As JS setPlayWhenReady(true) does: the setter alone.
    player.playWhenReady = true

    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }
  }

  @Test
  fun `setPlayWhenReady(true) after a cancelled retry reloads`() {
    load("https://127.0.0.1:9/stream", RetryPolicy.Infinite())
    player.play()
    idleUntil("a pending retry") { player.playbackError?.retrying == true }
    player.pause()
    idleUntil("an idle engine") { player.exoPlayer.playbackState == MediaPlayer.STATE_IDLE }

    player.playWhenReady = true

    idleUntil("the reload's retry") { player.playbackError?.retrying == true }
  }

  @Test
  fun `a setup's raised intent over a stop loads nothing`() {
    load(folder.silentWavUri(Duration.ofMillis(500)))
    player.stop()

    val before = states().size

    player.applySetupPlayWhenReady(true)
    idleFor(1)

    assertEquals(emptyList<PlaybackState>(), states().drop(before))

    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
    assertEquals(PlaybackState.STOPPED, player.playbackState)
  }

  @Test
  fun `a setup's raised intent over a terminal error loads nothing`() {
    load("https://127.0.0.1:9/stream", RetryPolicy.Limited(maxRetries = 0))
    val beforePlay = states().size
    player.play()
    idleUntil("the play's load to fail") { PlaybackState.ERROR in states().drop(beforePlay) }

    val before = states().size

    player.applySetupPlayWhenReady(true)
    idleFor(1)

    assertEquals(emptyList<PlaybackState>(), states().drop(before))

    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
    assertEquals(PlaybackState.ERROR, player.playbackState)
  }

  @Test
  fun `a setup's raised intent over a cancelled retry loads nothing`() {
    load("https://127.0.0.1:9/stream", RetryPolicy.Infinite())
    player.play()
    idleUntil("a pending retry") { player.playbackError?.retrying == true }
    player.pause()
    idleUntil("an idle engine") { player.exoPlayer.playbackState == MediaPlayer.STATE_IDLE }

    val before = states().size

    player.applySetupPlayWhenReady(true)
    idleFor(1)

    assertEquals(emptyList<PlaybackState>(), states().drop(before))

    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
    assertEquals(PlaybackState.PAUSED, player.playbackState)
  }

  @Test
  fun `a setup's raised intent plays a queue set afterwards`() {
    player.setup(PlayerSetupOptions())
    player.setCallbacks(callbacks)
    player.exoPlayer.trackSelectionParameters =
      player.exoPlayer.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .build()
    player.applySetupPlayWhenReady(true)

    player.setQueue(arrayOf(TestFixtures.track(src = folder.silentWavUri(Duration.ofMillis(500)))))

    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }
  }

  private fun load(src: String, retryPolicy: RetryPolicy = RetryPolicy.Default) {
    goOnline(context)
    player.setup(PlayerSetupOptions(retryPolicy = retryPolicy))
    player.setCallbacks(callbacks)
    player.exoPlayer.trackSelectionParameters =
      player.exoPlayer.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .build()
    player.exoPlayer.setMediaItems(listOf(TrackFactory.toMedia3(TestFixtures.track(src = src))))
    player.exoPlayer.prepare()
    idleFor(1)
  }

  private fun states() =
    mockingDetails(callbacks)
      .invocations
      .filter { it.method.name == "onPlaybackChanged" }
      .map { (it.arguments[0] as Playback).state }

  private fun idleFor(seconds: Int) =
    repeat(seconds * 10) {
      ShadowSystemClock.advanceBy(Duration.ofMillis(100))
      Thread.sleep(10)
      shadowOf(Looper.getMainLooper()).idle()
    }

  private fun idleUntil(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5_000
    while (!condition()) {
      if (System.currentTimeMillis() > deadline)
        fail(
          "timed out waiting for $what: ${player.playbackState}, exo ${player.exoPlayer.playbackState}"
        )
      ShadowSystemClock.advanceBy(Duration.ofMillis(100))
      Thread.sleep(10)
      shadowOf(Looper.getMainLooper()).idle()
    }
  }
}
