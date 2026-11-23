package com.audiobrowser.player

import android.os.Bundle
import androidx.media.utils.MediaConstants
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import com.audiobrowser.AudioBrowser
import com.audiobrowser.TestFixtures
import com.audiobrowser.TestFixtures.sectionStyle
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.buildService
import com.audiobrowser.session
import com.margelo.nitro.audiobrowser.StyleDisplay
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner

/** A browsed grid section with a `path` ends in a "view more" tile that has artwork to draw. */
@RunWith(RobolectricTestRunner::class)
class SessionViewMoreTest {

  private val controller = buildService()
  private val service = controller.get()
  private val player = service.player
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

  @After
  fun destroy() {
    controller.destroy()
  }

  private fun browse(page: String): List<MediaItem> {
    val section =
      TestFixtures.section(
        arrayOf(TestFixtures.track(title = "Station", id = "s1", src = "https://s/s1.mp3")),
        title = "Popular",
        path = "/popular",
        style = sectionStyle(display = StyleDisplay.GRID, artworkRendering = null, gridWrap = null),
      )
    val browserManager =
      BrowserManager().apply {
        config =
          BrowserConfig(
            androidControllerOfflineError = false,
            routes =
              arrayOf(
                TestFixtures.staticRoute(
                  page,
                  TestFixtures.resolvedTrack(path = page, sections = arrayOf(section)),
                )
              ),
          )
      }
    val configuration = TestFixtures.browserConfiguration()
    player.browser =
      mock(
        AudioBrowser::class.java,
        Answer {
          when {
            it.method.name == "getConfiguration" -> configuration
            it.method.name.startsWith("getBrowserManager") -> browserManager
            it.method.name == "gateDecision" -> AudioBrowser.GateOutcome(false, null)
            else -> RETURNS_DEFAULTS.answer(it)
          }
        },
      )
    player.notifyBrowserConfigurationReady()
    return player
      .getMediaSessionCallback()
      .onGetChildren(service.session, car, page, 0, 100, null)
      .get(5, TimeUnit.SECONDS)
      .value!!
  }

  @Test
  fun `a grid section's view more tile is served with the icon`() {
    val viewMore = browse("/home").last()

    assertEquals("/popular", viewMore.mediaId)
    assertEquals(
      "android.resource://${service.packageName}/drawable/audio_browser_view_more",
      viewMore.mediaMetadata.artworkUri.toString(),
    )
    assertEquals(
      MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM,
      viewMore.mediaMetadata.extras!!.getInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM
      ),
    )
  }
}
