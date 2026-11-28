package com.audiobrowser.player

import android.os.Bundle
import android.os.Looper
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.audiobrowser.AudioBrowser
import com.audiobrowser.TestFixtures.resolvedTrack
import com.audiobrowser.TestFixtures.section
import com.audiobrowser.TestFixtures.staticRoute
import com.audiobrowser.TestFixtures.track
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.BrowserPathHelper
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.RepeatMode
import com.margelo.nitro.audiobrowser.Track
import java.util.concurrent.ExecutionException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** A system resumption queues by the same rule as a restore at setup. */
@RunWith(RobolectricTestRunner::class)
class ResumptionQueueTest {
  private val player =
    Player(RuntimeEnvironment.getApplication()).apply { setup(PlayerSetupOptions()) }
  private val session = mock(MediaLibraryService.MediaLibrarySession::class.java)
  private val car =
    MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "car",
      0,
      0,
      MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
      MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
      false,
      Bundle.EMPTY,
      false,
    )

  @After fun release() = player.exoPlayer.release()

  private fun serve(page: String, vararg children: String) =
    serveTracks(page, *children.map { track(src = it) }.toTypedArray())

  private fun serveTracks(page: String, vararg children: Track) {
    val browserManager =
      BrowserManager().apply {
        config =
          BrowserConfig(
            routes =
              arrayOf(
                staticRoute(
                  page,
                  resolvedTrack(
                    path = page,
                    sections = arrayOf(section(children = arrayOf(*children))),
                  ),
                )
              )
          )
      }
    player.browser =
      mock(AudioBrowser::class.java).also { `when`(it.browserManager).thenReturn(browserManager) }
    player.notifyBrowserConfigurationReady()
  }

  private fun resume(): List<String> {
    val future = player.getMediaSessionCallback().onPlaybackResumption(session, car, true)
    for (attempt in 1..250) {
      shadowOf(Looper.getMainLooper()).idle()
      if (future.isDone) break
      Thread.sleep(20)
    }
    return future.get().mediaItems.map { TrackFactory.fromMedia3(it).src!! }
  }

  @Test
  fun `a resume inserts a track gone from its page at the top of its section`() {
    serve("/p", "a", "b")
    val stored = track(src = "gone").copy(path = BrowserPathHelper.build("/p", "gone", 0))
    player.setQueue(arrayOf(stored), prepare = false)
    player.playbackStateStore.save()

    assertEquals(listOf("gone", "a", "b"), resume())
  }

  @Test
  fun `a resume whose page fails resumes the stored queue`() {
    serve("/other")
    val stored = track(src = "b").copy(path = BrowserPathHelper.build("/p", "b", 1))
    player.setQueue(arrayOf(track(src = "a"), stored), startIndex = 1, prepare = false)
    player.playbackStateStore.save()

    assertEquals(listOf("a", "b"), resume())
  }

  @Test
  fun `a resume applies the stored repeat mode`() {
    serve("/p", "a")
    player.setQueue(arrayOf(track(src = "a")), prepare = false)
    player.playbackStateStore.save()
    player.playbackStateStore.repeatMode = RepeatMode.QUEUE

    resume()

    assertEquals(RepeatMode.QUEUE, player.repeatMode)
  }

  @Test
  fun `a resume after a clear finds no session`() {
    serve("/p", "a")
    player.setQueue(arrayOf(track(src = "a")), prepare = false)
    player.playbackStateStore.save()

    PlaybackStateStore.clear(RuntimeEnvironment.getApplication())

    val future = player.getMediaSessionCallback().onPlaybackResumption(session, car, true)
    for (attempt in 1..250) {
      shadowOf(Looper.getMainLooper()).idle()
      if (future.isDone) break
      Thread.sleep(20)
    }
    val failure = assertThrows(ExecutionException::class.java) { future.get() }
    assertEquals("No playback state to resume", failure.cause?.message)
  }

  @Test
  fun `a resume plays the stored queue when its page now disables the track`() {
    serveTracks("/p", track(src = "a"), track(src = "b", disabled = true))
    val stored = track(src = "b").copy(path = BrowserPathHelper.build("/p", "b", 1))
    player.setQueue(arrayOf(track(src = "a"), stored), startIndex = 1, prepare = false)
    player.playbackStateStore.save()

    assertEquals(listOf("a", "b"), resume())
  }
}
