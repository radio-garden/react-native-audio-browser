package com.audiobrowser.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Metadata
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.TimedMetadata
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlayerTrackChangeTest {

  private val player = Player(RuntimeEnvironment.getApplication())

  @After fun release() = player.exoPlayer.release()

  @Test
  fun `the timed metadata is cleared before the track change is announced`() {
    player.setup(PlayerSetupOptions())
    val item = TrackFactory.toMedia3(TestFixtures.track())
    player.sessionPlayer.setMediaItems(listOf(item))
    player.nowPlaying.onTimedMetadataReceived(
      TimedMetadata(title = "Song", artist = null, album = null, date = null, genre = null)
    )
    val seenAtTrackChange = mutableListOf<TimedMetadata?>()
    player.callbacks =
      mock(Callbacks::class.java) { invocation ->
        if (invocation.method.name == "onPlaybackActiveTrackChanged") {
          seenAtTrackChange.add(player.nowPlaying.timedMetadata)
        }
        Answers.RETURNS_DEFAULTS.answer(invocation)
      }

    player.playerListener.onMediaItemTransition(item, MediaPlayer.MEDIA_ITEM_TRANSITION_REASON_SEEK)

    assertEquals(listOf<TimedMetadata?>(null), seenAtTrackChange)
  }

  private fun icy(title: String) =
    Metadata(IcyInfo("StreamTitle='$title';".toByteArray(), title, null))

  private fun idle() {
    shadowOf(player.exoPlayer.playbackLooper).idle()
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun skip() = player.sessionPlayer.seekTo(1, 0)

  private fun playTwoTracks() {
    player.setup(PlayerSetupOptions())
    player.sessionPlayer.setMediaItems(
      listOf(
        TrackFactory.toMedia3(TestFixtures.track(title = "A", src = "https://s/a.mp3")),
        TrackFactory.toMedia3(TestFixtures.track(title = "B", src = "https://s/b.mp3")),
      )
    )
    idle()
    player.playerListener.onMetadata(icy("A song 1"))
  }

  /** Delivers an outgoing item's title the way MetadataRenderer does, posted before [switch]. */
  private fun assertOutgoingTitleDropped(switch: () -> Unit) {
    playTwoTracks()
    val playbackLooper = shadowOf(player.exoPlayer.playbackLooper)
    playbackLooper.pause()
    Handler(player.exoPlayer.playbackLooper).post {
      Handler(Looper.getMainLooper()).post { player.playerListener.onMetadata(icy("A song 2")) }
    }
    switch()
    playbackLooper.unPause()
    idle()

    assertNull(player.nowPlaying.timedMetadata)
  }

  @Test
  fun `a title the outgoing item had already posted does not survive a skip`() =
    assertOutgoingTitleDropped(::skip)

  @Test
  fun `a title the outgoing item had already posted does not survive a new queue`() =
    assertOutgoingTitleDropped {
      player.sessionPlayer.setMediaItems(
        listOf(TrackFactory.toMedia3(TestFixtures.track(title = "C", src = "https://s/c.mp3")))
      )
    }

  @Test
  fun `the next item's first title is kept when the queue advances on its own`() {
    playTwoTracks()
    player.playerListener.onMediaItemTransition(
      player.sessionPlayer.currentMediaItem,
      MediaPlayer.MEDIA_ITEM_TRANSITION_REASON_AUTO,
    )
    player.playerListener.onMetadata(icy("B song 1"))

    assertEquals("B song 1", player.nowPlaying.timedMetadata?.title)
  }

  @Test
  fun `the new item's first title is kept once the playback thread has caught up`() {
    playTwoTracks()
    skip()
    idle()
    player.playerListener.onMetadata(icy("B song 1"))

    assertEquals("B song 1", player.nowPlaying.timedMetadata?.title)
  }

  @Test
  fun `titles are kept after the engine is rebuilt during a track change`() {
    playTwoTracks()
    // Holds the old engine's playback thread until setup() waits on its release, so the thread
    // handles the fence with the release queued behind it.
    val oldPlaybackLooper = shadowOf(player.exoPlayer.playbackLooper)
    oldPlaybackLooper.pause()
    val testThread = Thread.currentThread()
    val releaser =
      thread(isDaemon = true) {
        val deadline = System.currentTimeMillis() + 10_000
        while (
          testThread.state != Thread.State.TIMED_WAITING && System.currentTimeMillis() < deadline
        ) {
          Thread.sleep(1)
        }
        oldPlaybackLooper.unPause()
      }
    skip()
    player.setup(PlayerSetupOptions(maxBuffer = 120_000.0))
    releaser.join()
    idle()
    player.playerListener.onMetadata(icy("B song 1"))

    assertEquals("B song 1", player.nowPlaying.timedMetadata?.title)
  }
}
