package com.audiobrowser.player

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.PlayingState
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Robolectric: Player builds a real ExoPlayer, whose audio focus handling the framework's
// AudioManager drives. With no decoder under Robolectric, the WAV plays on ExoPlayer's standalone
// clock, which still reaches READY and isPlaying. SDK 28: from 33, media3 asks
// AudioManager.getAudioDevicesForAttributes, which Robolectric 4.11 answers with null.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerSuppressionTest {

  @get:Rule val folder = TemporaryFolder()

  private val context = RuntimeEnvironment.getApplication()
  private val player = Player(context)
  private val callbacks = mock(Callbacks::class.java)
  private val audioManager =
    shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)

  @Before
  fun play() {
    player.setup(PlayerSetupOptions())
    player.setCallbacks(callbacks)
    val src = Uri.fromFile(silentWav()).toString()
    player.exoPlayer.setMediaItems(
      listOf(TrackFactory.toMedia3(TestFixtures.track(title = "a", src = src)))
    )
    player.exoPlayer.prepare()
    player.play()
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }
    clearInvocations(callbacks)
  }

  @After fun release() = player.exoPlayer.release()

  @Test
  fun `a transient focus loss reads as paused and keeps the intent`() {
    focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

    assertTrue(player.exoPlayer.playWhenReady)
    assertEquals(PlaybackState.PAUSED, player.playbackState)
    verify(callbacks).onPlaybackPlayingState(PlayingState(false, false))
    verify(callbacks).onPlaybackChanged(Playback(PlaybackState.PAUSED, null))
    assertTrue(
      mockingDetails(callbacks).invocations.none {
        it.method.name == "onPlaybackPlayWhenReadyChanged"
      }
    )
  }

  @Test
  fun `regaining focus reads as playing again`() {
    focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    clearInvocations(callbacks)

    focus(AudioManager.AUDIOFOCUS_GAIN)
    idleUntil("playing again") { player.playbackState == PlaybackState.PLAYING }

    verify(callbacks).onPlaybackPlayingState(PlayingState(true, false))
    verify(callbacks).onPlaybackChanged(Playback(PlaybackState.PLAYING, null))
  }

  @Test
  fun `the now playing formatter reads paused during a transient focus loss`() {
    val seen = mutableListOf<Boolean>()
    player.nowPlaying.formatter = { params ->
      seen += params.playWhenReady
      null
    }

    focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    assertEquals(false, seen.lastOrNull())

    focus(AudioManager.AUDIOFOCUS_GAIN)
    idleUntil("playing again") { player.playbackState == PlaybackState.PLAYING }
    assertEquals(true, seen.lastOrNull())
  }

  @Test
  fun `toggling during a transient focus loss keeps the intent`() {
    focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

    // As during a call: a request for focus would be refused.
    audioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

    player.togglePlayback()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(PlaybackState.PAUSED, player.playbackState)
    focus(AudioManager.AUDIOFOCUS_GAIN)

    assertTrue(player.exoPlayer.playWhenReady)
    idleUntil("playing again") { player.playbackState == PlaybackState.PLAYING }
  }

  @Test
  fun `pausing during a transient focus loss stays paused after it`() {
    focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

    player.pause()
    focus(AudioManager.AUDIOFOCUS_GAIN)

    assertFalse(player.exoPlayer.playWhenReady)
    assertEquals(PlaybackState.PAUSED, player.playbackState)
    assertEquals(PlayingState(false, false), player.getPlayingState())
  }

  /** Delivers [change] and waits for ExoPlayer's playback thread to report the suppression. */
  private fun focus(change: Int) {
    audioManager.lastAudioFocusRequest.listener.onAudioFocusChange(change)
    val suppressed = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    idleUntil("suppressed=$suppressed") {
      (player.exoPlayer.playbackSuppressionReason !=
        MediaPlayer.PLAYBACK_SUPPRESSION_REASON_NONE) == suppressed
    }
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
