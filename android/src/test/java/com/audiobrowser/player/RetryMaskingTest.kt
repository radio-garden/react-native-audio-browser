package com.audiobrowser.player

import android.os.Looper
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.C
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.controllerError
import androidx.media3.session.controllerPlaying
import androidx.media3.session.controllerState
import androidx.media3.session.legacyState
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.goOnline
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.RetryPolicy
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.PlaybackState
import java.io.IOException
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * While the retry loop holds the engine, its idle engine and its error reach no one: not JS (the
 * callbacks), not media3 controllers such as the notification (the session's player info), not
 * Android Auto and the system UI (the legacy session). Each surface is read after every turn of the
 * main looper, as a controller would be told. The READY blip needs decoded audio, which these tests
 * never play: [RetryViewTest] covers its view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RetryMaskingTest {

  private val context = RuntimeEnvironment.getApplication()
  private val player = Player(context)
  private val callbacks = mock(Callbacks::class.java)
  private lateinit var session: MediaSession

  @After
  fun release() {
    server.close()
    session.release()
    player.networkMonitor.destroy()
    player.exoPlayer.release()
  }

  private val server = ServerSocket(0)
  private val connections = AtomicInteger()

  init {
    // Takes each connection and ends it unanswered: a recoverable failure of a live stream.
    thread(isDaemon = true) {
      while (!server.isClosed) {
        try {
          server.accept().close()
          connections.incrementAndGet()
        } catch (_: IOException) {
          return@thread
        }
      }
    }
  }

  @Test
  fun `the loop reconnects, and its idle engine and error reach no surface`() {
    // The clock outruns a connection's real I/O: a slow one would bring a give-up into the test.
    val noGiveUp = Duration.ofHours(1).toMillis()
    play("http://127.0.0.1:${server.localPort}/live.mp3", RetryPolicy.Infinite(noGiveUp, noGiveUp))
    var held = 0
    stepUntil("the loop holding the idle engine a while") {
      if (player.exoPlayer.playbackState == MediaPlayer.STATE_IDLE) {
        assertTrue("the loop holds the idle engine", player.retryLoop!!.reconnecting)
        held++
      }
      if (player.retryLoop!!.reconnecting) assertLoading()
      held >= 30 && connections.get() >= 3
    }
    assertNoTerminalError()
  }

  @Test
  fun `a give-up while the loop holds the idle engine leaves no surface buffering`() {
    play(
      "http://127.0.0.1:${server.localPort}/live.mp3",
      RetryPolicy.Infinite(firstConnectMaxRetryDurationMs = 2_000),
    )
    stepUntil("the give-up") { player.playbackState == PlaybackState.ERROR }

    assertNotEquals("media3 controllers", MediaPlayer.STATE_BUFFERING, session.controllerState())
    assertNotEquals(
      "legacy controllers",
      PlaybackStateCompat.STATE_BUFFERING,
      session.legacyState(),
    )
  }

  private fun assertLoading() {
    assertTrue(
      "JS: ${player.playbackState}",
      player.playbackState in setOf(PlaybackState.LOADING, PlaybackState.BUFFERING),
    )
    assertEquals("media3 controllers", MediaPlayer.STATE_BUFFERING, session.controllerState())
    assertNull("media3 controllers", session.controllerError())
    assertFalse("media3 controllers", session.controllerPlaying())
    assertEquals("legacy controllers", PlaybackStateCompat.STATE_BUFFERING, session.legacyState())
  }

  private fun assertNoTerminalError() {
    assertFalse(
      "JS heard a terminal error",
      mockingDetails(callbacks).invocations.any {
        it.method.name == "onPlaybackError" && it.arguments[0] != null
      },
    )
    assertFalse(
      "JS heard the engine idle",
      mockingDetails(callbacks).invocations.any {
        it.method.name == "onPlaybackChanged" &&
          (it.arguments[0] as Playback).state in setOf(PlaybackState.NONE, PlaybackState.ERROR)
      },
    )
  }

  private fun play(src: String, retryPolicy: RetryPolicy = RetryPolicy.Infinite()) {
    goOnline(context)
    player.setup(PlayerSetupOptions(retryPolicy = retryPolicy))
    player.setCallbacks(callbacks)
    session = MediaSession.Builder(context, player.forwardingPlayer).build()
    player.setMediaSession(session)
    player.exoPlayer.trackSelectionParameters =
      player.exoPlayer.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .build()
    player.exoPlayer.setMediaItems(
      listOf(TrackFactory.toMedia3(TestFixtures.track(src = src).copy(live = true)))
    )
    player.exoPlayer.prepare()
    player.play()
  }

  /** Steps the clock 100 ms and the main looper a turn at a time, checking [step] after each. */
  private fun stepUntil(what: String, step: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (!step()) {
      if (System.currentTimeMillis() > deadline)
        fail(
          "timed out waiting for $what: ${player.playbackState}, exo ${player.exoPlayer.playbackState}"
        )
      ShadowSystemClock.advanceBy(Duration.ofMillis(100))
      Thread.sleep(10)
      val looper = shadowOf(Looper.getMainLooper())
      while (!looper.isIdle) {
        looper.runOneTask()
        step()
      }
    }
  }
}
