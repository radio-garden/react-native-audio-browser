package com.audiobrowser.player

import androidx.media3.common.HeartRating
import androidx.media3.session.MediaSession
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.util.TrackFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// Robolectric: Player builds a real ExoPlayer, which needs the framework and a main looper.
@RunWith(RobolectricTestRunner::class)
class FavoriteCapabilityTest {

  private val app = RuntimeEnvironment.getApplication()
  private val player = Player(app)
  private lateinit var session: MediaSession

  @After
  fun release() {
    session.release()
    player.exoPlayer.release()
    TrackFactory.favoriteEnabled = false
  }

  private fun favorite(enabled: Boolean) =
    PlayerUpdateOptions().let { it.copy(capabilities = it.capabilities.copy(favorite = enabled)) }

  private fun queuedRating() = player.exoPlayer.getMediaItemAt(0).mediaMetadata.userRating

  @Test
  fun `turning favoriting on and off restamps the queued items' heart rating`() {
    player.setup(PlayerSetupOptions())
    session = MediaSession.Builder(app, player.exoPlayer).build()
    player.setMediaSession(session)
    player.add(TestFixtures.track(title = "a", src = "https://s/a.mp3"))
    assertNull(queuedRating())

    player.applyOptions(favorite(true))
    assertFalse((queuedRating() as HeartRating).isRated)

    player.applyOptions(favorite(false))
    assertNull(queuedRating())
  }

  @Test
  fun `the restamp is one timeline change, not one per item`() {
    player.setup(PlayerSetupOptions())
    session = MediaSession.Builder(app, player.exoPlayer).build()
    player.setMediaSession(session)
    player.add(
      arrayOf(
        TestFixtures.track(title = "a", src = "https://s/a.mp3"),
        TestFixtures.track(title = "b", src = "https://s/b.mp3"),
        TestFixtures.track(title = "c", src = "https://s/c.mp3"),
      )
    )
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    var timelineChanges = 0
    player.exoPlayer.addListener(
      object : androidx.media3.common.Player.Listener {
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
          timelineChanges++
        }
      }
    )

    player.applyOptions(favorite(true))
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    assertEquals(1, timelineChanges)
  }
}
