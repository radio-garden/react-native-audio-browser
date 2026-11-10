package com.audiobrowser.player

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.controllerTitle
import androidx.media3.session.legacy.PlaybackStateCompat
import androidx.media3.session.legacyState
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.goOnline
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.RetryPolicy
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.PlaybackState
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
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
    play(RetryPolicy.Default, Uri.fromFile(silentWav()).toString())
    idleUntil("ENDED") { player.playbackState == PlaybackState.ENDED }

    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
    assertEquals(PlaybackStateCompat.STATE_STOPPED, session.legacyState())
  }

  @Test
  fun `the session hears the current track's title`() {
    play(RetryPolicy.Default, Uri.fromFile(silentWav()).toString())
    idleUntil("ENDED") { player.playbackState == PlaybackState.ENDED }

    assertEquals("T", session.controllerTitle())
  }

  private fun failTerminally() {
    play(RetryPolicy.Limited(maxRetries = 0))
    idleUntil("a terminal error") { player.playbackState == PlaybackState.ERROR }
  }

  private fun play(retryPolicy: RetryPolicy, src: String = "https://127.0.0.1:9/stream") {
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
    player.exoPlayer.setMediaItems(listOf(TrackFactory.toMedia3(TestFixtures.track(src = src))))
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

  /** Half a second of 8 kHz mono 8-bit silence. */
  private fun silentWav(): File {
    val samples = 4_000
    val header =
      ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt(36 + samples)
        put("WAVEfmt ".toByteArray())
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(8_000)
        putInt(8_000)
        putShort(1)
        putShort(8)
        put("data".toByteArray())
        putInt(samples)
      }
    return folder.newFile("silence.wav").apply {
      writeBytes(header.array() + ByteArray(samples) { 0x80.toByte() })
    }
  }
}
