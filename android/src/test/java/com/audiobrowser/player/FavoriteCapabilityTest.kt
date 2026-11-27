package com.audiobrowser.player

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.HeartRating
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.audiobrowser.AudioBrowser
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.browser.flattenedChildren
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.util.TrackFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
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

  private fun browserWith(browserManager: BrowserManager): AudioBrowser =
    mock(AudioBrowser::class.java).also { `when`(it.browserManager).thenReturn(browserManager) }

  /** The `favorited` a browse row gets for a track the app has declared a favorite. */
  private fun favoritedOnBrowseRow(browserManager: BrowserManager): Boolean? = runBlocking {
    val favorite = TestFixtures.track(title = "A", src = "https://s/a.mp3")
    val home = TestFixtures.resolvedTrack(path = "/home", children = arrayOf(favorite))
    browserManager.config = BrowserConfig(routes = arrayOf(TestFixtures.staticRoute("/home", home)))
    browserManager.setFavorites(listOf("https://s/a.mp3"))
    browserManager.resolve("/home").flattenedChildren?.single()?.favorited
  }

  private fun tapHeart(): Int {
    val controller =
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
    val command = SessionCommand(MediaSessionCommandManager.CUSTOM_ACTION_FAVORITE, Bundle.EMPTY)
    return player
      .getMediaSessionCallback()
      .onCustomCommand(session, controller, command, Bundle.EMPTY)
      .get()
      .resultCode
  }

  @Test
  fun `a heart tap toggles the active track's favorite`() {
    player.applyOptions(favorite(true))
    player.add(arrayOf(TestFixtures.track(title = "a", src = "https://s/a.mp3")))
    idle()

    assertEquals(SessionResult.RESULT_SUCCESS, tapHeart())
    assertEquals(true, player.currentTrack?.favorited)
    assertEquals(SessionResult.RESULT_SUCCESS, tapHeart())
    assertEquals(false, player.currentTrack?.favorited)
  }

  @Test
  fun `a heart tap with no active track reports an invalid state`() {
    player.applyOptions(favorite(true))
    idle()

    assertEquals(SessionResult.RESULT_ERROR_INVALID_STATE, tapHeart())
  }

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
  fun `the restamp is in place, and changes no track JS sees`() {
    player.add(
      arrayOf(
        TestFixtures.track(title = "a", src = "https://s/a.mp3"),
        TestFixtures.track(title = "b", src = "https://s/b.mp3"),
        TestFixtures.track(title = "c", src = "https://s/c.mp3"),
      )
    )
    player.exoPlayer.seekTo(1, 0)
    idle()
    val callbacks = mock(Callbacks::class.java)
    player.setCallbacks(callbacks)

    player.applyOptions(favorite(true))
    idle()

    val calls = mockingDetails(callbacks).invocations.map { it.method.name }
    assertEquals(0, calls.count { it == "onPlaybackQueueChanged" })
    assertEquals(0, calls.count { it == "onPlaybackActiveTrackChanged" })
    assertEquals(1, player.exoPlayer.currentMediaItemIndex)
    assertTrue(rating(1) is HeartRating)
  }

  @Test
  fun `a browser assigned after the favorite capability is on hydrates favorites`() {
    player.applyOptions(favorite(true))
    val browserManager = BrowserManager()

    player.browser = browserWith(browserManager)

    assertEquals(true, favoritedOnBrowseRow(browserManager))
  }

  @Test
  fun `a browser that replaces another hydrates favorites with the options unchanged`() {
    player.browser = browserWith(BrowserManager())
    player.applyOptions(favorite(true))
    val replacement = BrowserManager()

    player.applyOptions(favorite(true))
    player.browser = browserWith(replacement)

    assertEquals(true, favoritedOnBrowseRow(replacement))
  }
}
