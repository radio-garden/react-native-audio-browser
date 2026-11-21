package com.audiobrowser.player

import android.os.Bundle
import android.os.Looper
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.connectLegacyBrowser
import androidx.media3.session.legacyBrowserMaySearch
import com.audiobrowser.AudioBrowser
import com.audiobrowser.TestFixtures
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.buildService
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.session
import com.audiobrowser.util.JsPromise
import com.margelo.nitro.audiobrowser.FormattedNavigationError
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__shared_ptr_Promise_std__vector_Track______SearchParams
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.core.Promise
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * A legacy controller keeps the search grant it connects with, often before the browser is
 * configured, and media3 answers its search only through [MediaSessionCallback.onGetSearchResult],
 * after the search is announced.
 */
@RunWith(RobolectricTestRunner::class)
class SessionSearchCommandTest {
  private val app = RuntimeEnvironment.getApplication()
  private val service = buildService()
  private val player = service.get().player
  private val session = service.get().session
  /** Gives up on the browser at once, for the searches that wait in vain. */
  private val impatientPlayer =
    Player(app, browserRegistrationTimeout = 50.milliseconds).apply { setup(PlayerSetupOptions()) }
  private val librarySession = mock(MediaLibraryService.MediaLibrarySession::class.java)
  private val callback = player.getMediaSessionCallback()
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
  private var gateDecisions = 0

  @After
  fun release() {
    service.destroy()
    impatientPlayer.exoPlayer.release()
  }

  private fun offersSearch(): Boolean =
    callback
      .onConnect(session, car)
      .availableSessionCommands
      .contains(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)

  /** A browser whose search, if it has one, finds nothing. */
  private fun configureBrowser(search: Boolean, player: Player = this.player) {
    val nothing =
      JsPromise<Promise<Array<Track>>>().apply {
        resolve(JsPromise<Array<Track>>().apply { resolve(emptyArray()) }.promise)
      }
    val route =
      TestFixtures.staticRoute(
          BrowserManager.SEARCH_ROUTE_PATH,
          TestFixtures.resolvedTrack(path = BrowserManager.SEARCH_ROUTE_PATH),
        )
        .copy(
          browseStatic = null,
          searchCallback =
            Func_std__shared_ptr_Promise_std__shared_ptr_Promise_std__vector_Track______SearchParams {
              nothing.promise
            },
        )
    val browserManager =
      BrowserManager().apply {
        config = BrowserConfig(routes = if (search) arrayOf(route) else emptyArray())
      }
    player.browser =
      mock(
        AudioBrowser::class.java,
        Answer {
          when {
            it.method.name == "gateDecision" -> {
              gateDecisions++
              AudioBrowser.GateOutcome(false, null)
            }
            it.method.name.startsWith("getBrowserManager") -> browserManager
            it.method.name.startsWith("resolveFormattedError") -> FormattedNavigationError("", null)
            else -> RETURNS_DEFAULTS.answer(it)
          }
        },
      )
    player.notifyBrowserConfigurationReady()
  }

  /** The item counts announced to the car for [query] so far. */
  private fun announced(query: String): List<Int> {
    shadowOf(Looper.getMainLooper()).idle()
    return mockingDetails(librarySession)
      .invocations
      .filter {
        it.method.name == "notifySearchResultChanged" &&
          it.arguments[0] == car &&
          it.arguments[1] == query
      }
      .map { it.arguments[2] as Int }
  }

  /** Waits up to 5 s for the first announcement for [query]. */
  private fun awaitAnnounced(query: String): List<Int> {
    repeat(250) {
      announced(query)
        .takeIf { it.isNotEmpty() }
        ?.let {
          return it
        }
      Thread.sleep(20)
    }
    return emptyList()
  }

  @Test
  fun `a car connecting before the browser is configured is offered search`() {
    assertTrue(offersSearch())
  }

  @Test
  fun `an options update before the browser is configured keeps search offered`() {
    player.applyOptions(PlayerUpdateOptions(forwardJumpInterval = 30.0))

    assertTrue(offersSearch())
  }

  @Test
  fun `a browser configured with search keeps it offered`() {
    configureBrowser(search = true)

    assertTrue(offersSearch())
  }

  @Test
  fun `a browser configured without search withdraws it from cars that connect later`() {
    configureBrowser(search = false)

    assertFalse(offersSearch())
  }

  @Test
  fun `an options update after a browser without search keeps it withdrawn`() {
    configureBrowser(search = false)
    player.applyOptions(PlayerUpdateOptions(forwardJumpInterval = 30.0))

    assertFalse(offersSearch())
  }

  @Test
  fun `a search made before the browser is configured waits for it, then is announced`() {
    val search = callback.onSearch(librarySession, car, "x", null)
    assertFalse(search.isDone)
    assertEquals(emptyList<Int>(), announced("x"))

    configureBrowser(search = true)

    assertEquals(listOf(1), awaitAnnounced("x"))
  }

  @Test
  fun `a car connected while search was offered keeps it when the browser has none, and is refused`() {
    session.connectLegacyBrowser(car, callback.onConnect(session, car).availableSessionCommands)

    configureBrowser(search = false)
    shadowOf(Looper.getMainLooper()).idle()
    val search = callback.onSearch(librarySession, car, "x", null).get(5, TimeUnit.SECONDS)

    assertTrue(session.legacyBrowserMaySearch(car))
    assertEquals(SessionError.ERROR_NOT_SUPPORTED, search.resultCode)
    assertEquals(listOf(0), announced("x"))
  }

  @Test
  fun `a search the configured browser does not offer is announced, then refused`() {
    configureBrowser(search = false)

    val search = callback.onSearch(librarySession, car, "x", null).get(5, TimeUnit.SECONDS)
    val result =
      callback
        .onGetSearchResult(librarySession, car, "x", 0, Int.MAX_VALUE, null)
        .get(5, TimeUnit.SECONDS)

    assertEquals(SessionError.ERROR_NOT_SUPPORTED, search.resultCode)
    assertEquals(listOf(0), announced("x"))
    assertEquals(SessionError.ERROR_NOT_SUPPORTED, result.resultCode)
    assertEquals(0, gateDecisions)
  }

  @Test
  fun `a search whose browser is never configured is announced, then refused`() {
    val callback = impatientPlayer.getMediaSessionCallback()

    val search = callback.onSearch(librarySession, car, "x", null).get(5, TimeUnit.SECONDS)
    val result =
      callback
        .onGetSearchResult(librarySession, car, "x", 0, Int.MAX_VALUE, null)
        .get(5, TimeUnit.SECONDS)

    assertEquals(SessionError.ERROR_NOT_SUPPORTED, search.resultCode)
    assertEquals(listOf(0), announced("x"))
    assertEquals(SessionError.ERROR_NOT_SUPPORTED, result.resultCode)
  }

  @Test
  fun `results asked for before the browser is configured are refused at once`() {
    val result =
      callback
        .onGetSearchResult(librarySession, car, "x", 0, Int.MAX_VALUE, null)
        .get(1, TimeUnit.SECONDS)

    assertEquals(SessionError.ERROR_NOT_SUPPORTED, result.resultCode)
  }

  @Test
  fun `a refused search whose browser is configured before its results are asked for is served`() {
    val callback = impatientPlayer.getMediaSessionCallback()
    callback.onSearch(librarySession, car, "x", null).get(5, TimeUnit.SECONDS)

    configureBrowser(search = true, player = impatientPlayer)
    val result =
      callback
        .onGetSearchResult(librarySession, car, "x", 0, Int.MAX_VALUE, null)
        .get(5, TimeUnit.SECONDS)

    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
  }
}
