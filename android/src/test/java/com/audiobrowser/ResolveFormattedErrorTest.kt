package com.audiobrowser

import com.audiobrowser.util.FALLBACK_TIMEOUT
import com.audiobrowser.util.JsPromise
import com.margelo.nitro.audiobrowser.FormattedNavigationError
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__optional_FormattedNavigationError____FormatNavigationErrorParams
import com.margelo.nitro.audiobrowser.NativeBrowserConfiguration
import com.margelo.nitro.audiobrowser.NavigationError
import com.margelo.nitro.audiobrowser.NavigationErrorType
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock

class ResolveFormattedErrorTest {

  // AudioBrowser's constructor needs JNI: a mock running its real methods, with the configuration
  // written to the field configuration's setter writes.
  private fun browserFormattingWith(js: JsPromise<FormattedNavigationError?>): AudioBrowser {
    val browser = mock(AudioBrowser::class.java, CALLS_REAL_METHODS)
    val configuration =
      NativeBrowserConfiguration(
        path = null,
        request = null,
        requestResolver = null,
        browse = null,
        browseResolver = null,
        media = null,
        artwork = null,
        nowPlayingArtwork = null,
        routes = null,
        singleTrack = null,
        handleTrackLoad = null,
        androidControllerOfflineError = null,
        viewMoreTitle = null,
        carPlayLoadingTitle = null,
        resolveAlbumPath = null,
        formatNavigationError =
          Func_std__shared_ptr_Promise_std__optional_FormattedNavigationError____FormatNavigationErrorParams {
            js.promise
          },
      )
    AudioBrowser::class
      .java
      .getDeclaredField("_configuration")
      .apply { isAccessible = true }
      .set(browser, configuration)
    return browser
  }

  private val error = NavigationError(NavigationErrorType.EMPTY_CONTENT, "", null, null)
  private val default = defaultFormattedError(error)

  @Test
  fun `a formatter answering inside the timeout keeps the app's answer`() =
    runTest(timeout = 5.seconds) {
      val custom = FormattedNavigationError("No favorites yet", null)
      val js = JsPromise<FormattedNavigationError?>()
      val browser = browserFormattingWith(js)
      val formatted = backgroundScope.async { browser.resolveFormattedError(error, "/favorites") }
      runCurrent()

      advanceTimeBy(FALLBACK_TIMEOUT - 1.seconds)
      js.resolve(custom)
      runCurrent()

      assertTrue(formatted.isCompleted)
      assertEquals(custom, formatted.await())
    }

  @Test
  fun `a formatter that never answers falls back to the default at the timeout`() =
    runTest(timeout = 5.seconds) {
      val browser = browserFormattingWith(JsPromise())
      val formatted = backgroundScope.async { browser.resolveFormattedError(error, "/favorites") }

      advanceTimeBy(FALLBACK_TIMEOUT - 1.seconds)
      runCurrent()
      assertFalse(formatted.isCompleted)
      advanceTimeBy(2.seconds)
      runCurrent()

      assertTrue(formatted.isCompleted)
      assertEquals(default, formatted.await())
    }
}
