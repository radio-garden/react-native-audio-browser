package com.audiobrowser.player

import androidx.media3.session.MediaSession
import com.audiobrowser.AudioBrowser
import com.audiobrowser.TestFixtures.resolvedTrack
import com.audiobrowser.TestFixtures.staticRoute
import com.audiobrowser.TestFixtures.track
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
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
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BrowserFavoriteCapabilityTest {

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

  private fun favoriteCapability(enabled: Boolean) =
    PlayerUpdateOptions().let { it.copy(capabilities = it.capabilities.copy(favorite = enabled)) }

  private fun browserWith(browserManager: BrowserManager): AudioBrowser =
    mock(
      AudioBrowser::class.java,
      Answer {
        if (it.method.name.startsWith("getBrowserManager")) browserManager
        else RETURNS_DEFAULTS.answer(it)
      },
    )

  /** The `favorited` a browse row gets for a track the app has declared a favorite. */
  private fun favoritedOnBrowseRow(browserManager: BrowserManager): Boolean? = runBlocking {
    val favorite = track(title = "A", src = "https://s/a.mp3")
    val home = resolvedTrack(path = "/home", children = arrayOf(favorite))
    browserManager.config = BrowserConfig(routes = arrayOf(staticRoute("/home", home)))
    browserManager.setFavorites(listOf("https://s/a.mp3"))
    browserManager.resolve("/home").flattenedChildren?.single()?.favorited
  }

  @Test
  fun `a browser assigned after the favorite capability is on hydrates favorites`() {
    player.applyOptions(favoriteCapability(true))
    val browserManager = BrowserManager()

    player.browser = browserWith(browserManager)

    assertEquals(true, favoritedOnBrowseRow(browserManager))
  }

  @Test
  fun `a browser that replaces another hydrates favorites with the options unchanged`() {
    player.browser = browserWith(BrowserManager())
    player.applyOptions(favoriteCapability(true))
    val replacement = BrowserManager()

    player.applyOptions(favoriteCapability(true))
    player.browser = browserWith(replacement)

    assertEquals(true, favoritedOnBrowseRow(replacement))
  }
}
