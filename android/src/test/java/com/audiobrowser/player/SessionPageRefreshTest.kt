package com.audiobrowser.player

import android.os.Bundle
import androidx.media3.session.MediaSession
import com.audiobrowser.AudioBrowser
import com.audiobrowser.TestFixtures
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.buildService
import com.audiobrowser.session
import com.audiobrowser.util.BrowserPathHelper
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner

/** A level Android Auto re-queries because its page was refetched is not a visit. */
@RunWith(RobolectricTestRunner::class)
class SessionPageRefreshTest {
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
  private val browserManager = BrowserManager()

  @Before
  fun configure() {
    fun page(path: String) =
      TestFixtures.staticRoute(
        path,
        TestFixtures.resolvedTrack(
          path = path,
          sections =
            arrayOf(
              TestFixtures.section(
                arrayOf(TestFixtures.track(title = "Station", id = "s1", src = "https://s/s1.mp3"))
              )
            ),
        ),
      )
    browserManager.config =
      BrowserConfig(
        androidControllerOfflineError = false,
        routes = arrayOf(page("/home"), page("/favorites")),
      )
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
  }

  @After
  fun destroy() {
    controller.destroy()
  }

  private fun browse(path: String) {
    val callback = player.getMediaSessionCallback()
    callback.onSubscribe(service.session, car, path, null)
    callback.onGetChildren(service.session, car, path, 0, 100, null).get(5, TimeUnit.SECONDS)
  }

  private fun source() =
    browserManager.contextualPathFor("s1")?.let(BrowserPathHelper::stripTrackId)

  @Test
  fun `a level re-queried after its page was refreshed keeps each station's source page`() {
    browse("/home")
    browse("/favorites")

    player.notifyPageRefreshed("/home")
    browse("/home")

    assertEquals("/favorites", source())
  }

  @Test
  fun `a level browsed again moves each station's source page to it`() {
    browse("/home")
    browse("/favorites")

    browse("/home")

    assertEquals("/home", source())
  }
}
