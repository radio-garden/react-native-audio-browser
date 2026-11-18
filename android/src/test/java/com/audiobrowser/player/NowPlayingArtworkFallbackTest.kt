package com.audiobrowser.player

import android.os.Looper
import com.audiobrowser.AudioBrowser
import com.audiobrowser.TestFixtures.artworkConfig
import com.audiobrowser.TestFixtures.track
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.model.PlayerSetupOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import org.robolectric.Shadows.shadowOf

/** Without `nowPlayingArtwork`, the now-playing artwork resolves through `artwork`. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NowPlayingArtworkFallbackTest {

  private val player = Player(RuntimeEnvironment.getApplication())

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    val browserManager =
      BrowserManager().apply {
        config = BrowserConfig(artwork = artworkConfig(query = mapOf("sig" to "xyz")))
      }
    player.browser =
      mock(
        AudioBrowser::class.java,
        Answer {
          if (it.method.name.startsWith("getBrowserManager")) browserManager
          else RETURNS_DEFAULTS.answer(it)
        },
      )
    player.setup(PlayerSetupOptions())
  }

  @After
  fun release() {
    player.exoPlayer.release()
    Dispatchers.resetMain()
  }

  @Test
  fun `the now-playing artwork resolves through the artwork config`() = runTest {
    player.add(arrayOf(track(title = "a", artwork = "https://img.example.com/a.png")))
    shadowOf(Looper.getMainLooper()).idle()

    val published = player.exoPlayer.getMediaItemAt(0).mediaMetadata.artworkUri.toString()

    assertEquals(
      "https://img.example.com/a.png?sig=xyz",
      player.resolveDisplayArtwork(published, sizeHintPixels = null)?.uri,
    )
  }
}
