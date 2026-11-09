package com.audiobrowser.player

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.util.Util
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.PlaybackState
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerSetupEndedTest {

  @get:Rule val folder = TemporaryFolder()

  private val context = RuntimeEnvironment.getApplication()
  private val player = Player(context)
  private val callbacks = mock(Callbacks::class.java)

  @After fun release() = player.exoPlayer.release()

  @Test
  fun `a rebuild in ENDED stays ENDED and a later play restarts`() {
    endAndRebuild()

    // As JS setPlayWhenReady(true) does: the setter alone, without play()'s prepare().
    player.playWhenReady = true
    assertEquals(0L, player.exoPlayer.currentPosition)
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }
  }

  @Test
  fun `a session play after a rebuild in ENDED restarts`() {
    endAndRebuild()

    Util.handlePlayButtonAction(player.forwardingPlayer)
    assertEquals(0L, player.exoPlayer.currentPosition)
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }
  }

  @Test
  fun `a play after a rebuild in ENDED keeps a seek`() {
    endAndRebuild()

    player.seekTo(200, TimeUnit.MILLISECONDS)
    player.playWhenReady = true

    assertEquals(200L, player.exoPlayer.currentPosition)
  }

  @Test
  fun `a session play after a session stop on an ended queue restarts`() {
    end()
    player.forwardingPlayer.stop()

    // What media3 runs for a controller's play.
    Util.handlePlayButtonAction(player.forwardingPlayer)
    assertEquals(0L, player.exoPlayer.currentPosition)
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }
  }

  @Test
  fun `a play after a stop on an ended queue restarts`() {
    end()
    player.stop()

    player.play()
    assertEquals(0L, player.exoPlayer.currentPosition)
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }
  }

  private fun endAndRebuild() {
    end()

    val before = states().size
    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))
    disableAudio()
    idleFor(2)
    assertEquals(emptyList<PlaybackState>(), states().drop(before))
    assertEquals(PlaybackState.ENDED, player.playbackState)
  }

  private fun end() {
    player.setup(PlayerSetupOptions())
    player.setCallbacks(callbacks)
    disableAudio()
    player.exoPlayer.setMediaItems(
      listOf(
        TrackFactory.toMedia3(
          TestFixtures.track(title = "a", src = Uri.fromFile(silentWav()).toString())
        )
      )
    )
    player.exoPlayer.prepare()
    player.play()
    idleUntil("ENDED") { player.playbackState == PlaybackState.ENDED }
  }

  private fun states() =
    mockingDetails(callbacks)
      .invocations
      .filter { it.method.name == "onPlaybackChanged" }
      .map { (it.arguments[0] as Playback).state }

  private fun disableAudio() {
    player.exoPlayer.trackSelectionParameters =
      player.exoPlayer.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .build()
  }

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
          "timed out waiting for $what: ${player.playbackState}, " +
            "exo ${player.exoPlayer.playbackState}, ${player.exoPlayer.playerError}"
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
