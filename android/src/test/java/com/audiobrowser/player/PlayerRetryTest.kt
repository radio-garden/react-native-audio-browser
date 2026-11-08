package com.audiobrowser.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.RetryPolicy
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.PlaybackState
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowSystemClock

// SDK 28 and the disabled audio track as in PlayerSuppressionTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerRetryTest {

  @get:Rule val folder = TemporaryFolder()

  private val context = RuntimeEnvironment.getApplication()
  private val connectivity =
    shadowOf(context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
  private val wifi = ShadowNetwork.newInstance(103)
  private val player = Player(context)
  private val scope = CoroutineScope(Dispatchers.Unconfined)
  private val callbacks = mock(Callbacks::class.java)

  @After
  fun release() {
    scope.cancel()
    player.networkMonitor.destroy()
    player.exoPlayer.release()
  }

  @Test
  fun `a recovery keeps a load that has played`() {
    play(Uri.fromFile(silentWav()).toString())
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }

    player.playerListener.onPlayerError(
      PlaybackException("behind", null, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)
    )
    player.playerListener.onPlayerErrorChanged(null)

    assertTrue(player.hasPlayedCache)
  }

  @Test
  fun `a terminal error ends the load`() {
    play(Uri.fromFile(silentWav()).toString())
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }

    player.playerListener.onPlayerError(
      PlaybackException("failed", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    )

    assertEquals(PlaybackState.ERROR, player.playbackState)
    assertFalse(player.hasPlayedCache)
  }

  @Test
  fun `a pause keeps a terminal error`() {
    failTerminally()
    val failed = player.getPlayback()

    player.pause()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(failed, player.getPlayback())
    verify(callbacks, never()).onPlaybackError(null)
  }

  @Test
  fun `a pause during a retry stays paused and the next play reloads`() {
    pauseDuringRetry { player.pause() }

    player.play()
    awaitRetry("the reload's retry")
  }

  @Test
  fun `a session pause during a retry stays paused and a session play reloads`() {
    pauseDuringRetry { player.forwardingPlayer.pause() }

    // Masked as paused and ready, so the session's play button doesn't prepare it itself.
    assertEquals(MediaPlayer.STATE_READY, player.forwardingPlayer.playbackState)
    player.forwardingPlayer.play()
    awaitRetry("the reload's retry")
  }

  /** Pauses with a retry pending and runs past every backoff it would have taken. */
  private fun pauseDuringRetry(vararg next: String, pause: () -> Unit) {
    play("https://127.0.0.1:9/stream", *next, retryPolicy = RetryPolicy.Infinite())
    awaitRetry("a pending retry")

    pause()
    repeat(10) {
      ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
      Thread.sleep(20)
      shadowOf(Looper.getMainLooper()).idle()
    }

    assertEquals(Playback(PlaybackState.PAUSED, null), player.getPlayback())
    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
    assertFalse(
      "a terminal error was reported",
      mockingDetails(callbacks).invocations.any {
        it.method.name == "onPlaybackError" && it.arguments[0] != null
      },
    )
  }

  private fun awaitRetry(what: String) =
    idleUntil(what) {
      ShadowSystemClock.advanceBy(Duration.ofMillis(100))
      player.playbackError?.retrying == true
    }

  @Test
  fun `removing a failed current track plays the next`() {
    failTerminally(Uri.fromFile(silentWav()).toString())

    player.remove(listOf(0))

    idleUntil("the next track playing") { player.playbackState == PlaybackState.PLAYING }
  }

  @Test
  fun `removing another track keeps a terminal error`() {
    failTerminally(Uri.fromFile(silentWav()).toString())

    player.remove(listOf(1))
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
    assertEquals(PlaybackState.ERROR, player.playbackState)
  }

  @Test
  fun `removing the current track after a cancelled retry stays idle`() {
    pauseDuringRetry(Uri.fromFile(silentWav()).toString()) { player.pause() }

    player.remove(listOf(0))
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
  }

  @Test
  fun `emptying the queue clears a terminal error`() {
    failTerminally()

    player.clear()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(Playback(PlaybackState.NONE, null), player.getPlayback())
    verify(callbacks).onPlaybackError(null)
    verify(callbacks).onPlaybackChanged(Playback(PlaybackState.NONE, null))
  }

  @Test
  fun `a player that gave up stays stopped when the network returns`() {
    connectivity.setNetworkCapabilities(wifi, capabilities(validated = false))
    connectivity.networkCallbacks.forEach { it.onAvailable(wifi) }
    // A closed port: the load fails at once, the retry is pending, and the next failure is
    // terminal.
    play("https://127.0.0.1:9/stream", retryPolicy = RetryPolicy.Limited(maxRetries = 1))
    player.observeNetworkConnectivity(scope)
    idleUntil("the retry gave up") {
      ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
      player.playbackState == PlaybackState.ERROR
    }
    assertTrue(player.exoPlayer.playWhenReady)
    assertTrue(
      "a retry was pending",
      mockingDetails(callbacks).invocations.any {
        it.method.name == "onPlaybackChanged" &&
          (it.arguments[0] as Playback).error?.retrying == true
      },
    )

    connectivity.setNetworkCapabilities(wifi, capabilities(validated = true))
    connectivity.networkCallbacks.forEach {
      it.onCapabilitiesChanged(wifi, capabilities(validated = true))
    }
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
    assertEquals(PlaybackState.ERROR, player.playbackState)
  }

  /** A closed port: the load fails at once, and with no retries the failure is terminal. */
  private fun failTerminally(vararg next: String) {
    play("https://127.0.0.1:9/stream", *next, retryPolicy = RetryPolicy.Limited(maxRetries = 0))
    idleUntil("a terminal error") {
      ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
      player.playbackState == PlaybackState.ERROR
    }
  }

  private fun play(vararg srcs: String, retryPolicy: RetryPolicy = RetryPolicy.Default) {
    player.setup(PlayerSetupOptions(retryPolicy = retryPolicy))
    player.setCallbacks(callbacks)
    player.exoPlayer.trackSelectionParameters =
      player.exoPlayer.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .build()
    player.exoPlayer.setMediaItems(
      srcs.mapIndexed { i, src ->
        TrackFactory.toMedia3(TestFixtures.track(title = "$i", src = src))
      }
    )
    player.exoPlayer.prepare()
    player.play()
  }

  private fun capabilities(validated: Boolean) =
    ShadowNetworkCapabilities.newInstance().also {
      shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      if (validated) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

  private fun idleUntil(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5_000
    while (!condition()) {
      if (System.currentTimeMillis() > deadline)
        fail(
          "timed out waiting for $what: ${player.playbackState}, " +
            "exo ${player.exoPlayer.playbackState}, ${player.exoPlayer.playerError}"
        )
      Thread.sleep(10)
      shadowOf(Looper.getMainLooper()).idle()
    }
  }

  /** A minute of 8 kHz mono 8-bit silence, long enough that no test reaches its end. */
  private fun silentWav(): File {
    val samples = 8_000 * 60
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
