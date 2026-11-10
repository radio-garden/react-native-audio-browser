package com.audiobrowser.player

import com.audiobrowser.AudioBrowser
import com.audiobrowser.TestFixtures
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.JsPromise
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__shared_ptr_Promise_std__vector_Track______SearchParams
import com.margelo.nitro.audiobrowser.MediaReference
import com.margelo.nitro.audiobrowser.SearchParams
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.core.Promise
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlayFromSearchTest {
  private val player =
    Player(RuntimeEnvironment.getApplication()).apply { setup(PlayerSetupOptions()) }

  @After fun release() = player.exoPlayer.release()

  private val params =
    SearchParams(
      mode = null,
      query = "a",
      genre = null,
      artist = null,
      album = null,
      title = null,
      playlist = null,
      reference = MediaReference.UNKNOWN,
    )

  /** A browser whose search callback hands back [answer], behind Nitro's bridge promise. */
  private fun browserWhoseSearchAnswers(answer: JsPromise<Array<Track>>) {
    val bridge = JsPromise<Promise<Array<Track>>>().apply { resolve(answer.promise) }
    val browserManager =
      BrowserManager().apply {
        config =
          BrowserConfig(
            routes =
              arrayOf(
                TestFixtures.staticRoute(
                    BrowserManager.SEARCH_ROUTE_PATH,
                    TestFixtures.resolvedTrack(path = BrowserManager.SEARCH_ROUTE_PATH),
                  )
                  .copy(
                    browseStatic = null,
                    searchCallback =
                      Func_std__shared_ptr_Promise_std__shared_ptr_Promise_std__vector_Track______SearchParams {
                        bridge.promise
                      },
                  )
              )
          )
      }
    player.browser =
      mock(
        AudioBrowser::class.java,
        Answer {
          when {
            it.method.name == "gateDecision" -> AudioBrowser.GateOutcome(false, null)
            it.method.name.startsWith("getBrowserManager") -> browserManager
            else -> RETURNS_DEFAULTS.answer(it)
          }
        },
      )
    player.notifyBrowserConfigurationReady()
  }

  @Test
  fun `a search cancelled while its callback is silent ends and plays nothing`() =
    runTest(timeout = 5.seconds) {
      val answer = JsPromise<Array<Track>>()
      browserWhoseSearchAnswers(answer)
      val caller = backgroundScope.async { player.playFromSearch(params) }
      runCurrent()

      caller.cancel()
      runCurrent()
      answer.resolve(arrayOf(TestFixtures.track(title = "a")))
      runCurrent()

      assertTrue(caller.isCompleted)
      assertTrue(caller.isCancelled)
      assertEquals(emptyList<String>(), player.tracks.map { it.title })
    }

  @Test
  fun `a search that times out waiting for AudioBrowser reports failure`() = runTest {
    assertFalse(player.playFromSearch(params))
  }
}
