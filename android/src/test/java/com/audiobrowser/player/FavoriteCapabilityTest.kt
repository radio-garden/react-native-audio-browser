package com.audiobrowser.player

import android.os.Looper
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.Timeline
import androidx.media3.session.MediaSession
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.util.TrackFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FavoriteCapabilityTest {

  private val app = RuntimeEnvironment.getApplication()
  private val player = Player(app)
  private lateinit var session: MediaSession

  @Before
  fun setUp() {
    player.setup(PlayerSetupOptions())
    session = MediaSession.Builder(app, player.exoPlayer).build()
    player.setMediaSession(session)
  }

  @After
  fun release() {
    session.release()
    player.exoPlayer.release()
    TrackFactory.favoriteEnabled = false
  }

  private fun favorite(enabled: Boolean) =
    PlayerUpdateOptions().let { it.copy(capabilities = it.capabilities.copy(favorite = enabled)) }

  private fun rating(index: Int) = player.exoPlayer.getMediaItemAt(index).mediaMetadata.userRating

  private fun idle() = shadowOf(Looper.getMainLooper()).idle()

  @Test
  fun `turning favoriting on and off restamps each queued item's own heart rating`() {
    player.add(
      arrayOf(
        TestFixtures.track(title = "a", src = "https://s/a.mp3"),
        TestFixtures.track(title = "b", src = "https://s/b.mp3", favorited = true),
        TestFixtures.track(title = "c", src = "https://s/c.mp3", favorited = false),
      )
    )
    assertNull(rating(0))
    assertNull(rating(1))

    player.applyOptions(favorite(true))
    assertFalse((rating(0) as HeartRating).isRated)
    assertEquals(HeartRating(true), rating(1))
    assertEquals(HeartRating(false), rating(2))

    player.applyOptions(favorite(false))
    assertNull(rating(0))
    assertNull(rating(1))
    assertNull(rating(2))
  }

  @Test
  fun `the restamp is one timeline change, in place`() {
    player.add(
      arrayOf(
        TestFixtures.track(title = "a", src = "https://s/a.mp3"),
        TestFixtures.track(title = "b", src = "https://s/b.mp3"),
        TestFixtures.track(title = "c", src = "https://s/c.mp3"),
      )
    )
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

    player.applyOptions(favorite(true))
    idle()

    assertEquals(1, timelineChanges)
    assertEquals(0, transitions)
    assertEquals(1, player.exoPlayer.currentMediaItemIndex)
    assertTrue(rating(1) is HeartRating)
  }
}
