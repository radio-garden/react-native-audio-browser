package com.audiobrowser.player

import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.FALLBACK_TIMEOUT
import com.audiobrowser.util.JsPromise
import com.margelo.nitro.audiobrowser.FormatNowPlayingParams
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__optional_NowPlayingUpdate____FormatNowPlayingParams
import com.margelo.nitro.audiobrowser.NowPlayingUpdate
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The now-playing formatter as setup hands it to the updater, unwrapped from the Nitro Func. */
@RunWith(RobolectricTestRunner::class)
class NowPlayingFormatterTest {
  private val js = JsPromise<NowPlayingUpdate?>()
  private val player =
    Player(RuntimeEnvironment.getApplication()).apply {
      setup(
        PlayerSetupOptions(
          nowPlayingMetadataFormatter =
            Func_std__shared_ptr_Promise_std__optional_NowPlayingUpdate____FormatNowPlayingParams {
              js.promise
            }
        )
      )
    }

  @After fun release() = player.exoPlayer.release()

  private val params = FormatNowPlayingParams(TestFixtures.track("Station"), null, true, null, null)

  @Test
  fun `a formatter answering inside the timeout lands its answer`() =
    runTest(timeout = 5.seconds) {
      val answer = NowPlayingUpdate(title = null, artist = "Song - Artist", album = null)
      val formatted = backgroundScope.async { player.nowPlaying.formatter!!(params) }
      runCurrent()

      advanceTimeBy(FALLBACK_TIMEOUT - 1.seconds)
      js.resolve(answer)
      runCurrent()

      assertTrue(formatted.isCompleted)
      assertEquals(answer, formatted.await())
    }

  @Test
  fun `a formatter that never answers fails at the timeout`() =
    runTest(timeout = 5.seconds) {
      val formatted =
        backgroundScope.async { runCatching { player.nowPlaying.formatter!!(params) } }

      advanceTimeBy(FALLBACK_TIMEOUT - 1.seconds)
      runCurrent()
      assertFalse(formatted.isCompleted)
      advanceTimeBy(2.seconds)
      runCurrent()

      assertTrue(formatted.isCompleted)
      assertTrue(formatted.await().exceptionOrNull() is TimeoutException)
    }
}
