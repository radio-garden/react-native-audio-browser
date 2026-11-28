package com.audiobrowser.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.MediaItem
import com.audiobrowser.TestFixtures
import com.audiobrowser.goOnline
import com.audiobrowser.liveHlsUri
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.RetryPolicy
import com.audiobrowser.silentMp3Bytes
import com.audiobrowser.silentWavUri
import com.audiobrowser.util.TrackFactory
import java.io.IOException
import java.net.ServerSocket
import java.time.Duration
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/** A skip to the playing item, from the app or a controller's pick of it, as iOS does it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SkipInPlaceTest {

  @get:Rule val folder = TemporaryFolder()

  private val context = RuntimeEnvironment.getApplication()
  private val player = Player(context)

  /** The engine's state changes and seeks, from when the test starts watching. */
  private val engineEvents = mutableListOf<String>()

  private var server: ServerSocket? = null

  @After
  fun release() {
    player.destroy()
    server?.close()
  }

  /** An ICY-like stream: a minute of MP3 sent without a length, the connection held open. */
  private fun streamWithoutLength(): String {
    val server = ServerSocket(0).also { server = it }
    thread(isDaemon = true) {
      while (!server.isClosed) {
        val socket =
          try {
            server.accept()
          } catch (_: IOException) {
            return@thread
          }
        thread(isDaemon = true) {
          socket.use {
            val request = it.getInputStream().bufferedReader()
            while (!request.readLine().isNullOrEmpty()) Unit
            it.getOutputStream().run {
              write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\n\r\n".toByteArray())
              write(silentMp3Bytes(Duration.ofMinutes(1)))
              flush()
            }
            while (!server.isClosed) Thread.sleep(10)
          }
        }
      }
    }
    return "http://127.0.0.1:${server.localPort}/stream.mp3"
  }

  @Test
  fun `a skip to the playing live item leaves it playing`() {
    playUntilReady(folder.liveHlsUri(segments = 6))

    player.skipTo(0)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(emptyList<String>(), engineEvents)
  }

  @Test
  fun `a skip to the playing stream without a length leaves it playing`() {
    playUntilReady(streamWithoutLength())

    player.skipTo(0)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(emptyList<String>(), engineEvents)
  }

  @Test
  fun `a skip to the playing live item behind its edge rejoins it`() {
    playUntilReady(folder.liveHlsUri(segments = 6))
    player.exoPlayer.seekTo(0)
    idleUntil("ready behind the edge") { player.exoPlayer.playbackState == MediaPlayer.STATE_READY }
    engineEvents.clear()

    player.skipTo(0)
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue("seek" in engineEvents)
  }

  @Test
  fun `a playing live item given a buffering state doesn't skip in place`() {
    playUntilReady(folder.liveHlsUri(segments = 6))

    assertFalse(player.sessionPlayer.skipsInPlace(0, MediaPlayer.STATE_BUFFERING))
  }

  @Test
  fun `a stream without a length reads as live and unseekable`() {
    playUntilReady(streamWithoutLength())

    assertTrue(player.isCurrentItemLive)
    assertFalse(player.exoPlayer.isCurrentMediaItemSeekable)
  }

  @Test
  fun `a seek to the live edge of a live stream the track doesn't declare live does nothing`() {
    playUntilReady(streamWithoutLength())

    player.seekToLiveEdge()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(emptyList<String>(), engineEvents)
  }

  @Test
  fun `a skip to the paused live item rejoins its live edge`() {
    playUntilReady(folder.liveHlsUri(segments = 6))
    player.pause()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(MediaPlayer.STATE_READY, player.exoPlayer.playbackState)

    player.skipTo(0)
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue("seek" in engineEvents)
  }

  @Test
  fun `a skip to the failed item loads it again`() {
    play("https://127.0.0.1:9/stream", RetryPolicy.Limited(maxRetries = 0))
    idleUntil("the load to fail") { player.exoPlayer.playerError != null }
    watchEngine()

    player.skipTo(0)
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue("state ${MediaPlayer.STATE_BUFFERING}" in engineEvents)
  }

  @Test
  fun `a skip to the playing on-demand item starts it over`() {
    playUntilReady(folder.silentWavUri(Duration.ofMinutes(1)))
    player.exoPlayer.seekTo(30_000)
    idleUntil("ready at 30 s") { player.exoPlayer.playbackState == MediaPlayer.STATE_READY }
    assertTrue(player.exoPlayer.currentPosition >= 30_000)

    player.skipTo(0)
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue(player.exoPlayer.currentPosition < 1_000)
  }

  @Test
  fun `a controller's pick of the playing live item leaves it playing`() {
    playUntilReady(folder.liveHlsUri(segments = 6))

    // What Media3 runs with the items onSetMediaItems returns for a pick in the queue's own list.
    val items = player.tracks.map { TrackFactory.toMedia3(it) }
    player.forwardingPlayer.setMediaItems(items, 0, C.TIME_UNSET)
    player.forwardingPlayer.prepare()
    player.forwardingPlayer.play()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(emptyList<String>(), engineEvents)
  }

  @Test
  fun `a skip from the playing live item to another moves to it`() {
    playUntilReady(folder.liveHlsUri(segments = 6))

    player.skipTo(1)

    assertEquals(1, player.exoPlayer.currentMediaItemIndex)
  }

  @Test
  fun `a controller's pick of another item in the queue moves to it`() {
    playUntilReady(folder.liveHlsUri(segments = 6))

    val items = player.tracks.map { TrackFactory.toMedia3(it) }
    player.forwardingPlayer.setMediaItems(items, 1, C.TIME_UNSET)

    assertEquals(1, player.exoPlayer.currentMediaItemIndex)
  }

  @Test
  fun `a controller's pick of another item in the queue seeks to it`() {
    playUntilReady(folder.liveHlsUri(segments = 6))

    val items = player.tracks.map { TrackFactory.toMedia3(it) }
    player.forwardingPlayer.setMediaItems(items, 1, C.TIME_UNSET)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(
      listOf("transition ${MediaPlayer.MEDIA_ITEM_TRANSITION_REASON_SEEK}"),
      engineEvents.filter { it.startsWith("transition") },
    )
  }

  @Test
  fun `a controller's pick in the queue starts at the position it asks for`() {
    playUntilReady(folder.silentWavUri(Duration.ofMinutes(1)))

    val items = player.tracks.map { TrackFactory.toMedia3(it) }
    player.forwardingPlayer.setMediaItems(items, 0, 30_000)
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue("seek" in engineEvents)
    assertTrue(player.exoPlayer.currentPosition >= 30_000)
  }

  @Test
  fun `a controller's other queue at the playing live item's index replaces it`() {
    playUntilReady(folder.liveHlsUri(segments = 6))
    val others =
      listOf("2", "3").map {
        TrackFactory.toMedia3(TestFixtures.track(title = it, src = "https://s/$it.mp3"))
      }

    player.forwardingPlayer.setMediaItems(others, 0, C.TIME_UNSET)

    assertEquals(others[0].mediaId, player.exoPlayer.currentMediaItem?.mediaId)
  }

  private fun playUntilReady(src: String) {
    play(src)
    idleUntil("ready") { player.exoPlayer.playbackState == MediaPlayer.STATE_READY }
    watchEngine()
  }

  private fun watchEngine() {
    player.exoPlayer.addListener(
      object : MediaPlayer.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
          engineEvents += "state $playbackState"
        }

        override fun onPositionDiscontinuity(
          oldPosition: MediaPlayer.PositionInfo,
          newPosition: MediaPlayer.PositionInfo,
          reason: Int,
        ) {
          if (reason == MediaPlayer.DISCONTINUITY_REASON_SEEK) engineEvents += "seek"
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
          engineEvents += "transition $reason"
        }
      }
    )
  }

  private fun play(src: String, retryPolicy: RetryPolicy = RetryPolicy.Default) {
    goOnline(context)
    player.setup(PlayerSetupOptions(retryPolicy = retryPolicy))
    player.exoPlayer.trackSelectionParameters =
      player.exoPlayer.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .build()
    player.setQueue(
      arrayOf(
        TestFixtures.track(title = "0", src = src),
        TestFixtures.track(title = "1", src = "https://s/1.mp3"),
      )
    )
    player.play()
  }

  private fun idleUntil(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5_000
    while (!condition()) {
      if (System.currentTimeMillis() > deadline)
        fail("timed out waiting for $what: ${player.playbackState}")
      ShadowSystemClock.advanceBy(Duration.ofMillis(100))
      Thread.sleep(10)
      shadowOf(Looper.getMainLooper()).idle()
    }
  }
}
