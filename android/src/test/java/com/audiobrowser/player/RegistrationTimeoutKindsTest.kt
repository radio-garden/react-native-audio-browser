package com.audiobrowser.player

import android.os.Bundle
import android.os.Looper
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.audiobrowser.AudioBrowser
import com.audiobrowser.TestFixtures
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.goOnline
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.util.JsPromise
import com.audiobrowser.util.SearchParamsFactory
import com.google.common.util.concurrent.ListenableFuture
import com.margelo.nitro.audiobrowser.FailedRequestKind
import com.margelo.nitro.audiobrowser.FormattedNavigationError
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__shared_ptr_Promise_std__vector_Track______SearchParams
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.core.Promise
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RegistrationTimeoutKindsTest {
  private val app = RuntimeEnvironment.getApplication()
  private val player =
    Player(app, browserRegistrationTimeout = 50.milliseconds).apply { setup(PlayerSetupOptions()) }
  private val session = mock(MediaLibraryService.MediaLibrarySession::class.java)
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

  @Before
  fun forgetTimeouts() {
    RegistrationTimeouts.take()
  }

  @After fun release() = player.exoPlayer.release()

  private fun timeouts() = RegistrationTimeouts.take().map { it.kind to it.count }

  /** Waits up to 5 s for [future], running what it posts to main meanwhile. */
  private fun settle(future: ListenableFuture<*>) {
    repeat(250) {
      shadowOf(Looper.getMainLooper()).idle()
      if (future.isDone) return
      Thread.sleep(20)
    }
  }

  @Test
  fun `a resumption counts as resume`() {
    player.setQueue(arrayOf(TestFixtures.track(title = "Saved", id = "saved")))
    player.playbackStateStore.save()

    settle(player.getMediaSessionCallback().onPlaybackResumption(session, car, true))

    assertEquals(listOf(FailedRequestKind.RESUME to 1.0), timeouts())
  }

  @Test
  fun `a voice search counts as playFromSearch`() = runTest {
    try {
      player.voiceSearch(SearchParamsFactory.fromPlayFromSearch("jazz", null))
    } catch (_: TimeoutException) {}

    assertEquals(listOf(FailedRequestKind.PLAYFROMSEARCH to 1.0), timeouts())
  }

  @Test
  fun `a play-from-search intent counts as playFromSearch`() = runTest {
    assertFalse(player.playFromSearch(SearchParamsFactory.fromPlayFromSearch("jazz", null)))

    assertEquals(listOf(FailedRequestKind.PLAYFROMSEARCH to 1.0), timeouts())
  }

  @Test
  fun `an offline search re-run once back online counts nothing`() {
    player.setMediaSession(session)
    // Hands the callback the session it announces re-runs to.
    player.applyOptions(PlayerUpdateOptions(forwardJumpInterval = 30.0))
    configureBrowserWithSearch()
    // Robolectric starts offline, so the search serves the offline tile and waits for the network.
    settle(player.getMediaSessionCallback().onSearch(session, car, "jazz", null))
    verify(session).notifySearchResultChanged(car, "jazz", 1, null)
    player.browser = null

    goOnline(app)
    // Well past the 50 ms wait the re-run makes.
    repeat(100) {
      shadowOf(Looper.getMainLooper()).idle()
      Thread.sleep(20)
    }

    assertEquals(emptyList<Pair<FailedRequestKind, Double>>(), timeouts())
  }

  private fun configureBrowserWithSearch() {
    val found =
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
              found.promise
            },
        )
    val browserManager = BrowserManager().apply { config = BrowserConfig(routes = arrayOf(route)) }
    player.browser =
      mock(
        AudioBrowser::class.java,
        Answer {
          when {
            it.method.name == "gateDecision" -> AudioBrowser.GateOutcome(false, null)
            it.method.name.startsWith("getBrowserManager") -> browserManager
            it.method.name.startsWith("resolveFormattedError") -> FormattedNavigationError("", null)
            else -> RETURNS_DEFAULTS.answer(it)
          }
        },
      )
    player.notifyBrowserConfigurationReady()
  }
}
