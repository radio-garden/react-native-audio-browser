package com.audiobrowser.player

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.remote.FakeRemote
import com.audiobrowser.silentWavUri
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.PlayingState
import com.margelo.nitro.audiobrowser.RemoteOutputsOptions
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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

// Robolectric's clock stands still, so a delayed message on ExoPlayer's playback thread never
// runs: the audio track is disabled because an audio renderer can wait in BUFFERING on one. A
// rebuild builds an engine with it enabled, so idle() disables it on each new engine.
// SDK 28: from 33, media3 asks AudioManager.getAudioDevicesForAttributes, which Robolectric 4.11
// answers with null.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerSuppressionTest {

  @get:Rule val folder = TemporaryFolder()

  private val context = RuntimeEnvironment.getApplication()
  private val remote = FakeRemote(Looper.getMainLooper())
  private val player =
    Player(
      context,
      castRemote = { _, _ -> remote },
      sonosRemote = { _, _ -> FakeRemote(Looper.getMainLooper()) },
    )
  private val callbacks = mock(Callbacks::class.java)
  private val audioManager =
    shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)

  @Before
  fun play() {
    player.setup(PlayerSetupOptions())
    player.setCallbacks(callbacks)
    val src = folder.silentWavUri(Duration.ofMinutes(1))
    disableAudio()
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

    // As during a call: a request for focus is refused.
    audioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

    player.togglePlayback()
    idle()
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

  @Test
  fun `a rebuild during a transient focus loss stays suppressed until focus returns`() {
    focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    val engine = player.exoPlayer

    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))
    idle()

    assertSame(engine, player.exoPlayer)
    assertSuppressed()
    focus(AudioManager.AUDIOFOCUS_GAIN)
    idleUntil("playing on a new engine") {
      player.playbackState == PlaybackState.PLAYING && player.exoPlayer !== engine
    }
  }

  @Test
  fun `a rebuild during a call keeps the intent`() {
    focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    audioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))
    // ExoPlayer requests focus on its playback thread and posts a refusal back.
    Thread.sleep(200)
    idle()

    assertSuppressed()
    audioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    focus(AudioManager.AUDIOFOCUS_GAIN)
    idleUntil("playing again") { player.playbackState == PlaybackState.PLAYING }
  }

  @Test
  fun `a rebuild held during a transient focus loss runs when the intent drops`() {
    listOf<() -> Unit>(
        { focus(AudioManager.AUDIOFOCUS_LOSS) },
        { player.pause() },
        { player.stop() },
      )
      .forEachIndexed { i, drop ->
        player.play()
        idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }
        focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        val engine = player.exoPlayer
        // A different maxBuffer each time, so each setup() rebuilds.
        player.setup(PlayerSetupOptions(maxBuffer = 120_000.0 + i))
        assertSame(engine, player.exoPlayer)
        drop()

        idle()

        assertNotSame(engine, player.exoPlayer)
        assertFalse(player.exoPlayer.playWhenReady)
      }
    assertEquals(PlaybackState.STOPPED, player.playbackState)
  }

  @Test
  fun `a play during a transient focus loss runs a held rebuild after it`() {
    focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    val engine = player.exoPlayer
    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))
    assertSame(engine, player.exoPlayer)

    // ExoPlayer's play() asks for focus again, so it plays over the transient loss.
    player.play()
    // The rebuild follows the play: it is posted, never run inside it.
    assertSame(engine, player.exoPlayer)
    idle()

    assertNotSame(engine, player.exoPlayer)
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }
  }

  @Test
  fun `an engine stopped during a transient focus loss holds no rebuild`() {
    focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    // As a handover to a remote output does: the engine stops but keeps the intent.
    player.exoPlayer.stop()
    idle()
    val engine = player.exoPlayer

    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))

    assertNotSame(engine, player.exoPlayer)
  }

  @Test
  fun `a held rebuild runs when a remote output takes over`() {
    player.setup(
      PlayerSetupOptions(
        remoteOutputs =
          RemoteOutputsOptions(cast = true, castReceiverApplicationId = null, sonos = null)
      )
    )
    focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    val engine = player.exoPlayer
    player.setup(
      PlayerSetupOptions(
        maxBuffer = 120_000.0,
        remoteOutputs =
          RemoteOutputsOptions(cast = true, castReceiverApplicationId = null, sonos = null),
      )
    )
    assertSame(engine, player.exoPlayer)

    remote.startSession()
    idleUntil("remote") { !player.localEngine.isActive }
    idle()

    assertNotSame(engine, player.exoPlayer)
    assertFalse(player.localEngine.isActive)
  }

  @Test
  fun `a held rebuild runs when the engine idles`() {
    focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    val engine = player.exoPlayer
    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))
    assertSame(engine, player.exoPlayer)

    // As a terminal error does: the engine idles with the intent and the suppression unchanged.
    player.exoPlayer.stop()
    idle()

    assertNotSame(engine, player.exoPlayer)
  }

  @Test
  fun `the last setup during a transient focus loss wins`() {
    focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    val engines = mutableSetOf(player.exoPlayer)

    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))
    engines += player.exoPlayer
    player.setup(PlayerSetupOptions(maxBuffer = 130_000.0))
    engines += player.exoPlayer
    focus(AudioManager.AUDIOFOCUS_GAIN)
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }
    val engine = player.exoPlayer
    engines += engine

    assertEquals("one rebuild", 2, engines.size)

    player.setup(PlayerSetupOptions(maxBuffer = 130_000.0))
    assertSame(engine, player.exoPlayer)
  }

  @Test
  fun `a setup back to the current options during a transient focus loss cancels the rebuild`() {
    focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    val engine = player.exoPlayer

    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))
    player.setup(PlayerSetupOptions())
    focus(AudioManager.AUDIOFOCUS_GAIN)
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }

    assertSame(engine, player.exoPlayer)
  }

  @Test
  fun `destroying the player drops a held rebuild`() {
    focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    val engine = player.exoPlayer
    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))

    player.destroy()
    idle()

    assertSame(engine, player.exoPlayer)
  }

  private fun assertSuppressed() {
    assertTrue(player.exoPlayer.playWhenReady)
    assertEquals(PlaybackState.PAUSED, player.playbackState)
    assertEquals(PlayingState(false, false), player.getPlayingState())
  }

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
      idle()
    }
  }

  private fun idle() {
    shadowOf(Looper.getMainLooper()).idle()
    if (C.TRACK_TYPE_AUDIO !in player.exoPlayer.trackSelectionParameters.disabledTrackTypes)
      disableAudio()
  }

  private fun disableAudio() {
    player.exoPlayer.trackSelectionParameters =
      player.exoPlayer.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .build()
  }
}
