package com.audiobrowser.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.Timeline
import androidx.media3.session.MediaSession
import androidx.media3.session.controllerTitle
import androidx.media3.session.countLegacyStateUpdates
import androidx.media3.session.legacy.PlaybackStateCompat
import androidx.media3.session.legacyState
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.goOnline
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.RetryPolicy
import com.audiobrowser.silentWavBytes
import com.audiobrowser.silentWavUri
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.PlaybackState
import java.io.IOException
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * What the session tells legacy controllers (Android Auto, Bluetooth, the system UI) for each state
 * on an idle engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionViewTest {

  @get:Rule val folder = TemporaryFolder()

  private val context = RuntimeEnvironment.getApplication()
  private val player = Player(context)
  private val callbacks = mock(Callbacks::class.java)
  private lateinit var session: MediaSession

  private val paused = PlaybackStateCompat.STATE_PAUSED
  private val idle = PlaybackStateCompat.STATE_NONE

  @After
  fun release() {
    session.release()
    player.networkMonitor.destroy()
    player.exoPlayer.release()
  }

  @Test
  fun `a cancelled retry reads paused`() {
    play(RetryPolicy.Infinite())
    idleUntil("a pending retry") { player.playbackError?.retrying == true }

    player.pause()
    idleUntil("an idle engine") { player.exoPlayer.playbackState == MediaPlayer.STATE_IDLE }

    assertEquals(PlaybackState.PAUSED, player.playbackState)
    assertEquals(paused, session.legacyState())
  }

  @Test
  fun `a terminal error reads paused`() {
    failTerminally()

    assertEquals(paused, session.legacyState())
  }

  @Test
  fun `an error a rebuild dropped reads paused`() {
    failTerminally()

    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(PlaybackState.ERROR, player.playbackState)
    assertEquals(paused, session.legacyState())
  }

  @Test
  fun `a stop after a terminal error reads idle`() {
    failTerminally()

    player.stop()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(idle, session.legacyState())
  }

  @Test
  fun `a stop after a cancelled retry reads idle`() {
    play(RetryPolicy.Infinite())
    idleUntil("a pending retry") { player.playbackError?.retrying == true }
    player.pause()
    idleUntil("an idle engine") { player.exoPlayer.playbackState == MediaPlayer.STATE_IDLE }

    player.stop()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(idle, session.legacyState())
  }

  @Test
  fun `an ended queue a rebuild carried idle reads ended`() {
    play(RetryPolicy.Default, folder.silentWavUri(Duration.ofMillis(500)))
    idleUntil("ENDED") { player.playbackState == PlaybackState.ENDED }

    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
    assertEquals(PlaybackStateCompat.STATE_STOPPED, session.legacyState())
  }

  @Test
  fun `the session hears the current track's title`() {
    play(RetryPolicy.Default, folder.silentWavUri(Duration.ofMillis(500)))
    idleUntil("ENDED") { player.playbackState == PlaybackState.ENDED }

    assertEquals("T", session.controllerTitle())
  }

  @Test
  fun `a reconnect attempt tells the session nothing it was already told`() {
    val server = ServerSocket(0)
    val connections = AtomicInteger()
    val drop = CountDownLatch(1)
    // Serves five seconds of a stream until the drop, then ends each connection unanswered.
    thread(isDaemon = true) {
      server.accept().use { socket ->
        val request = socket.getInputStream().bufferedReader()
        while (!request.readLine().isNullOrEmpty()) Unit
        socket.getOutputStream().run {
          write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
          write(silentWavBytes(Duration.ofMinutes(1)), 0, 40_000)
          flush()
        }
        drop.await(30, TimeUnit.SECONDS)
      }
      while (!server.isClosed) {
        try {
          server.accept().close()
          connections.incrementAndGet()
        } catch (_: IOException) {
          return@thread
        }
      }
    }
    try {
      play(RetryPolicy.Infinite(), "http://127.0.0.1:${server.localPort}/live", live = true)
      idleUntil("PLAYING") { player.playbackState == PlaybackState.PLAYING }
      drop.countDown()
      // Stands in for the drop, which the engine never reads: its disabled audio track stops it
      // reading the stream.
      player.exoPlayer.stop()
      player.exoPlayer.prepare()
      idleUntil("the first reconnect") { connections.get() >= 1 }
      val states = session.countLegacyStateUpdates()
      val timelines = AtomicInteger()
      player.forwardingPlayer.addListener(
        object : MediaPlayer.Listener {
          override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            timelines.incrementAndGet()
          }
        }
      )

      idleUntil("three more reconnects") { connections.get() >= 4 }

      assertEquals(PlaybackState.BUFFERING, player.playbackState)
      assertEquals(0, states())
      assertEquals(0, timelines.get())
    } finally {
      server.close()
    }
  }

  private fun failTerminally() {
    play(RetryPolicy.Limited(maxRetries = 0))
    idleUntil("a terminal error") { player.playbackState == PlaybackState.ERROR }
  }

  private fun play(
    retryPolicy: RetryPolicy,
    src: String = "https://127.0.0.1:9/stream",
    live: Boolean? = null,
  ) {
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
      listOf(TrackFactory.toMedia3(TestFixtures.track(src = src).copy(live = live)))
    )
    player.exoPlayer.prepare()
    player.play()
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
