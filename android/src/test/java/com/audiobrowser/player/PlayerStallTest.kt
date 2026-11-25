package com.audiobrowser.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.util.StuckPlayerException
import com.audiobrowser.TestFixtures
import com.audiobrowser.goOnline
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.RetryPolicy
import com.audiobrowser.silentWavUri
import com.audiobrowser.util.JsPromise
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.FormatNowPlayingParams
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__optional_NowPlayingUpdate____FormatNowPlayingParams
import com.margelo.nitro.audiobrowser.NowPlayingUpdate
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.StallReason
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

// SDK 28 and the disabled audio track as in PlayerRetryTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerStallTest {

  @get:Rule val folder = TemporaryFolder()

  private val context = RuntimeEnvironment.getApplication()
  private val seen = mutableListOf<FormatNowPlayingParams>()
  private var onFormat: (FormatNowPlayingParams) -> Unit = {}
  private val player = Player(context)

  @Before fun online() = goOnline(context)

  @After fun release() = player.destroy()

  @Test
  fun `the initial connect is not a stall`() {
    play()
    player.setPlaybackState(PlaybackState.BUFFERING)

    assertFalse(player.isStalled)
  }

  @Test
  fun `buffering after playing is a stall, and reaches the formatter`() {
    playUntilPlaying()

    player.setPlaybackState(PlaybackState.BUFFERING)
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue(player.isStalled)
    assertEquals(StallReason.BUFFERING, seen.last().stalled)
  }

  @Test
  fun `buffering while paused is not a stall`() {
    playUntilPlaying()
    player.pause()

    player.setPlaybackState(PlaybackState.BUFFERING)

    assertFalse(player.isStalled)
  }

  @Test
  fun `buffering after a seek is not a stall`() {
    playUntilPlaying()

    seek()
    player.setPlaybackState(PlaybackState.BUFFERING)

    assertFalse(player.isStalled)
  }

  @Test
  fun `a seek during a stall ends it on the now-playing line`() {
    playUntilPlaying()
    player.setPlaybackState(PlaybackState.BUFFERING)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(StallReason.BUFFERING, seen.last().stalled)

    seek()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(PlaybackState.BUFFERING, player.playbackState)
    assertEquals(null, seen.last().stalled)
  }

  @Test
  fun `the end of an item, waiting on the next, is not a stall`() {
    ServerSocket(0).use { silent ->
      val renders = mutableListOf<Triple<Int?, PlaybackState, StallReason?>>()
      onFormat = { renders.add(Triple(player.currentIndex, player.playbackState, it.stalled)) }
      play(
        srcs =
          listOf(
            folder.silentWavUri(Duration.ofSeconds(1)),
            "http://127.0.0.1:${silent.localPort}/next.mp3",
          )
      )

      idleUntil("the next item") {
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        player.currentIndex == 1
      }
      shadowOf(Looper.getMainLooper()).idle()

      assertTrue(renders.contains(Triple(0, PlaybackState.PLAYING, null)))
      assertEquals(emptyList<StallReason>(), renders.mapNotNull { it.third })
    }
  }

  @Test
  fun `buffering after a load is not a stall`() {
    val src = playUntilPlaying()

    player.load(TestFixtures.track("Other", src = src))
    player.setPlaybackState(PlaybackState.BUFFERING)

    assertFalse(player.isStalled)
  }

  @Test
  fun `a reconnect after an error is a fresh connect, not a stall`() {
    val src = playUntilPlaying(retryPolicy = RetryPolicy.Infinite())
    // The reconnect fails to load, so it never plays again.
    File(URI(src)).delete()

    player.exoPlayer.stop()
    player.playerListener.onPlayerError(
      PlaybackException(
        "stuck",
        StuckPlayerException(StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS, 60_000),
        PlaybackException.ERROR_CODE_TIMEOUT,
      )
    )
    idleUntil("the reconnect") {
      ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
      player.exoPlayer.playbackState != MediaPlayer.STATE_IDLE
    }
    player.setPlaybackState(PlaybackState.BUFFERING)

    assertTrue(player.shouldPlayWhenReady)
    assertFalse(player.isStalled)
  }

  @Test
  fun `a play after a stop is a fresh connect, not a stall`() {
    val src = playUntilPlaying()
    player.stop()
    shadowOf(Looper.getMainLooper()).idle()
    // The connect fails to load, so it never plays again.
    File(URI(src)).delete()

    player.play()
    idleUntil("the connect") { player.exoPlayer.playbackState != MediaPlayer.STATE_IDLE }
    player.setPlaybackState(PlaybackState.BUFFERING)

    assertTrue(player.shouldPlayWhenReady)
    assertFalse(player.isStalled)
  }

  @Test
  fun `an engine rebuild while playing is a fresh connect, not a stall`() {
    playUntilPlaying()

    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))

    assertEquals(PlaybackState.BUFFERING, player.playbackState)
    assertTrue(player.shouldPlayWhenReady)
    assertFalse(player.isStalled)
  }

  /** As the engine reports a seek, before the BUFFERING it brings. */
  private fun seek() {
    val position =
      MediaPlayer.PositionInfo(null, 0, null, null, 0, 30_000, 30_000, C.INDEX_UNSET, C.INDEX_UNSET)
    player.playerListener.onPositionDiscontinuity(
      position,
      position,
      MediaPlayer.DISCONTINUITY_REASON_SEEK,
    )
  }

  private fun play(
    tracks: Int = 1,
    retryPolicy: RetryPolicy = RetryPolicy.Default,
    srcs: List<String>? = null,
  ): String {
    val src = srcs?.first() ?: folder.silentWavUri(Duration.ofMinutes(1))
    player.setup(
      PlayerSetupOptions(
        retryPolicy = retryPolicy,
        nowPlayingMetadataFormatter =
          Func_std__shared_ptr_Promise_std__optional_NowPlayingUpdate____FormatNowPlayingParams {
            seen.add(it)
            onFormat(it)
            JsPromise<NowPlayingUpdate?>().promise
          },
      )
    )
    player.exoPlayer.trackSelectionParameters =
      player.exoPlayer.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .build()
    player.exoPlayer.setMediaItems(
      (srcs ?: List(tracks) { src }).mapIndexed { i, it ->
        TrackFactory.toMedia3(TestFixtures.track(title = "$i", src = it))
      }
    )
    player.exoPlayer.prepare()
    player.play()
    return src
  }

  private fun playUntilPlaying(
    tracks: Int = 1,
    retryPolicy: RetryPolicy = RetryPolicy.Default,
  ): String {
    val src = play(tracks, retryPolicy)
    idleUntil("playing") { player.playbackState == PlaybackState.PLAYING }
    return src
  }

  private fun idleUntil(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5_000
    while (!condition()) {
      if (System.currentTimeMillis() > deadline)
        fail("timed out waiting for $what: ${player.playbackState}")
      Thread.sleep(10)
      shadowOf(Looper.getMainLooper()).idle()
    }
  }
}
