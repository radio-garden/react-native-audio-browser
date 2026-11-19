package com.audiobrowser.player

import android.os.Looper
import androidx.media3.session.MediaSession
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.PlayerUpdateOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * What a controller's pick of a queue item does to the player the session wraps, with the
 * `skipToQueueItem` capability on and off.
 */
@RunWith(RobolectricTestRunner::class)
class QueueItemPickTest {

  private val app = RuntimeEnvironment.getApplication()
  private val player = Player(app)
  private lateinit var session: MediaSession

  @Before
  fun setUp() {
    player.setup(PlayerSetupOptions())
    session = MediaSession.Builder(app, player.forwardingPlayer).build()
    player.setMediaSession(session)
    player.add(
      arrayOf(
        TestFixtures.track(title = "a", src = "https://s/a.mp3"),
        TestFixtures.track(title = "b", src = "https://s/b.mp3"),
        TestFixtures.track(title = "c", src = "https://s/c.mp3"),
      )
    )
    idle()
  }

  @After
  fun release() {
    session.release()
    player.exoPlayer.release()
  }

  private fun skipToQueueItem(enabled: Boolean?) =
    player.applyOptions(
      PlayerUpdateOptions().let {
        it.copy(capabilities = it.capabilities.copy(skipToQueueItem = enabled))
      }
    )

  private fun idle() = shadowOf(Looper.getMainLooper()).idle()

  private val index
    get() = player.exoPlayer.currentMediaItemIndex

  @Test
  fun `a pick of another item moves the queue by default`() {
    player.forwardingPlayer.seekToDefaultPosition(2)
    idle()
    assertEquals(2, index)

    player.forwardingPlayer.seekTo(1, 0)
    idle()
    assertEquals(1, index)
  }

  @Test
  fun `a pick of another item is dropped while the capability is off`() {
    skipToQueueItem(false)

    player.forwardingPlayer.seekToDefaultPosition(2)
    idle()
    assertEquals(0, index)
  }

  @Test
  fun `a seek into another item is dropped while the capability is off`() {
    skipToQueueItem(false)

    player.forwardingPlayer.seekTo(2, 0)
    idle()
    assertEquals(0, index)

    player.forwardingPlayer.seekTo(1, 5_000)
    idle()
    assertEquals(0, index)
    assertEquals(0L, player.exoPlayer.currentPosition)
  }

  @Test
  fun `a seek within the current item goes through while the capability is off`() {
    player.exoPlayer.seekTo(1, 0)
    idle()
    skipToQueueItem(false)

    player.forwardingPlayer.seekTo(1, 5_000)
    idle()
    assertEquals(1, index)
    assertEquals(5_000L, player.exoPlayer.currentPosition)

    player.forwardingPlayer.seekToDefaultPosition(1)
    idle()
    assertEquals(1, index)
    assertEquals(0L, player.exoPlayer.currentPosition)
  }

  @Test
  fun `the app's own skip moves the queue while the capability is off`() {
    skipToQueueItem(false)

    player.skipTo(2)
    idle()
    assertEquals(2, index)
  }

  @Test
  fun `turning the capability back on lets a pick through again`() {
    skipToQueueItem(false)
    skipToQueueItem(true)

    player.forwardingPlayer.seekToDefaultPosition(2)
    idle()
    assertEquals(2, index)
  }
}
