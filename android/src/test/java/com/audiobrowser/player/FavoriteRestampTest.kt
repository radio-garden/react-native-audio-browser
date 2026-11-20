package com.audiobrowser.player

import android.os.Looper
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.Timeline
import androidx.media3.session.MediaSession
import com.audiobrowser.AudioBrowser
import com.audiobrowser.TestFixtures
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.browser.FavoriteChange
import com.audiobrowser.browser.flattenedChildren
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.util.TrackFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FavoriteRestampTest {

  private val app = RuntimeEnvironment.getApplication()
  private val player = Player(app)
  private lateinit var session: MediaSession

  private val a = TestFixtures.track(title = "a", src = "https://s/a.mp3", favorited = false)
  private val b = TestFixtures.track(title = "b", src = "https://s/b.mp3", favorited = false)

  @Before
  fun setUp() {
    player.setup(PlayerSetupOptions())
    session = MediaSession.Builder(app, player.exoPlayer).build()
    player.setMediaSession(session)
    player.applyOptions(
      PlayerUpdateOptions().let { it.copy(capabilities = it.capabilities.copy(favorite = true)) }
    )
  }

  @After
  fun release() {
    session.release()
    player.exoPlayer.release()
    TrackFactory.favoriteEnabled = false
  }

  private fun idle() = shadowOf(Looper.getMainLooper()).idle()

  private fun favorited() = player.tracks.map { it.favorited }

  private fun ratings() =
    (0 until player.exoPlayer.mediaItemCount).map {
      (player.exoPlayer.getMediaItemAt(it).mediaMetadata.userRating as HeartRating).isHeart
    }

  private fun heartButton() =
    session.mediaButtonPreferences.single { it.displayName.toString().contains("favorites") }

  @Test
  fun `a toggle restamps every queued copy of the active track`() {
    player.add(arrayOf(a, b, a))
    idle()

    player.setActiveTrackFavorited(true)

    assertEquals(listOf(true, false, true), favorited())
    assertEquals(listOf(true, false, true), ratings())
  }

  @Test
  fun `a toggle restamps the content on screen`() = runBlocking {
    val browserManager = BrowserManager()
    browserManager.config =
      BrowserConfig(
        routes =
          arrayOf(
            TestFixtures.staticRoute(
              "/home",
              TestFixtures.resolvedTrack(path = "/home", children = arrayOf(a, b)),
            )
          )
      )
    browserManager.navigate("/home")
    player.browser =
      mock(AudioBrowser::class.java).also { `when`(it.browserManager).thenReturn(browserManager) }
    player.add(arrayOf(a))
    idle()

    player.setActiveTrackFavorited(true)

    assertEquals(
      listOf(true, false),
      browserManager.getContent()?.flattenedChildren?.map { it.favorited },
    )
  }

  @Test
  fun `a favorite change restamps the queue in place, once, and the heart follows`() {
    player.add(arrayOf(b, a, b))
    player.exoPlayer.seekTo(1, 0)
    idle()
    var timelineChanges = 0
    var transitions = 0
    player.exoPlayer.addListener(
      object : MediaPlayer.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
          timelineChanges++
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
          transitions++
        }
      }
    )

    player.applyFavoriteChange(FavoriteChange.between(emptySet(), setOf("https://s/a.mp3"))!!)
    idle()

    assertEquals(listOf(false, true, false), favorited())
    assertEquals(listOf(false, true, false), ratings())
    assertEquals(1, timelineChanges)
    assertEquals(0, transitions)
    assertEquals(1, player.exoPlayer.currentMediaItemIndex)
    assertEquals("Remove from favorites", heartButton().displayName.toString())
  }

  @Test
  fun `a favorite change that moves no queued track changes nothing`() {
    player.add(arrayOf(a))
    idle()
    var timelineChanges = 0
    player.exoPlayer.addListener(
      object : MediaPlayer.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
          timelineChanges++
        }
      }
    )

    player.applyFavoriteChange(FavoriteChange.between(emptySet(), setOf("elsewhere"))!!)
    idle()

    assertEquals(0, timelineChanges)
  }
}
