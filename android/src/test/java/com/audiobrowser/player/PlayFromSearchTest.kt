package com.audiobrowser.player

import com.audiobrowser.AudioBrowser
import com.audiobrowser.TestFixtures
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.model.PlayerSetupOptions
import com.margelo.nitro.audiobrowser.MediaReference
import com.margelo.nitro.audiobrowser.SearchParams
import com.margelo.nitro.audiobrowser.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  // Answered by method name: the methods are suspend, so Mockito's matchers can't reach their
  // continuation parameter.
  private fun browserWhoseSearchAnswers(search: () -> Array<Track>) {
    val browserManager =
      mock(
        BrowserManager::class.java,
        Answer { if (it.method.name == "searchPlayable") search() else RETURNS_DEFAULTS.answer(it) },
      )
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
  fun `a search whose callback answers after its caller was cancelled plays nothing`() = runTest {
    lateinit var caller: Job
    // A Nitro promise's await ignores cancellation, so the callback answers anyway.
    browserWhoseSearchAnswers {
      caller.cancel()
      arrayOf(TestFixtures.track(title = "a"))
    }

    caller = launch { player.playFromSearch(params) }
    caller.join()

    assertEquals(emptyList<String>(), player.tracks.map { it.title })
  }

  @Test
  fun `a search that times out waiting for AudioBrowser reports failure`() = runTest {
    assertFalse(player.playFromSearch(params))
  }
}
