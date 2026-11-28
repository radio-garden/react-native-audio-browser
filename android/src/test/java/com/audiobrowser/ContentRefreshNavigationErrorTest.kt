package com.audiobrowser

import com.audiobrowser.TestFixtures.resolvedTrack
import com.audiobrowser.TestFixtures.staticRoute
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.util.JsPromise
import com.margelo.nitro.audiobrowser.BrowseError
import com.margelo.nitro.audiobrowser.FormattedNavigationError
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__variant_ResolvedTrack__BrowseError__std__shared_ptr_Promise_std__variant_ResolvedTrack__BrowseError_______BrowserSourceCallbackParam
import com.margelo.nitro.audiobrowser.NativeBrowserConfiguration
import com.margelo.nitro.audiobrowser.NavigationError
import com.margelo.nitro.audiobrowser.NavigationErrorType
import com.margelo.nitro.audiobrowser.Variant_ResolvedTrack_BrowseError_Promise_BrowseResult_
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

/**
 * The navigation error across the reload behind `notifyContentChanged` and `invalidateAllContent`.
 * Every page is served by a callback route whose answer the test settles, so a reload can load or
 * fail on demand. Robolectric provides `android.net.Uri` and `android.util.LruCache`.
 */
@RunWith(RobolectricTestRunner::class)
class ContentRefreshNavigationErrorTest {

  private class Request(val path: String) {
    private val js = JsPromise<Variant_ResolvedTrack_BrowseError_Promise_BrowseResult_>()
    val promise = js.promise

    fun load(title: String = "Loaded") =
      js.resolve(
        Variant_ResolvedTrack_BrowseError_Promise_BrowseResult_.create(
          resolvedTrack(path = path, title = title)
        )
      )

    fun fail() =
      js.resolve(
        Variant_ResolvedTrack_BrowseError_Promise_BrowseResult_.create(BrowseError("Offline"))
      )
  }

  private val requests = mutableListOf<Request>()
  private val errorEvents = mutableListOf<NavigationError?>()
  private val formattedEvents = mutableListOf<FormattedNavigationError?>()

  private fun callbackRoute(path: String) =
    staticRoute(path, resolvedTrack(path = path))
      .copy(
        browseStatic = null,
        browseCallback =
          Func_std__shared_ptr_Promise_std__variant_ResolvedTrack__BrowseError__std__shared_ptr_Promise_std__variant_ResolvedTrack__BrowseError_______BrowserSourceCallbackParam {
            Request(it.path).also { request -> requests += request }.promise
          },
      )

  // AudioBrowser's constructor needs JNI: a mock running its real methods, with the fields the
  // navigation and reload paths read written in its place.
  private fun TestScope.browser(): AudioBrowser {
    val browser = mock(AudioBrowser::class.java, CALLS_REAL_METHODS)
    val manager = BrowserManager(pageContext = StandardTestDispatcher(testScheduler))
    manager.config = BrowserConfig(routes = arrayOf(callbackRoute("/a"), callbackRoute("/b")))
    set(browser, "browserManager", manager)
    set(browser, "mainScope", CoroutineScope(StandardTestDispatcher(testScheduler)))
    set(
      browser,
      "_configuration",
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
        viewMoreArtwork = null,
        carPlayLoadingTitle = null,
        resolveAlbumPath = null,
        formatNavigationError = null,
        findTrack = null,
      ),
    )
    browser.onNavigationError = { errorEvents += it.error }
    browser.onFormattedNavigationError = { formattedEvents += it }
    return browser
  }

  private fun set(browser: AudioBrowser, field: String, value: Any) {
    AudioBrowser::class
      .java
      .getDeclaredField(field)
      .apply { isAccessible = true }
      .set(browser, value)
  }

  /** Leaves [path] displayed with a navigation error, as a navigation that failed offline does. */
  private fun TestScope.failNavigation(browser: AudioBrowser, path: String) {
    browser.navigatePath(path)
    runCurrent()
    requests.removeAt(0).fail()
    runCurrent()
    assertEquals(NavigationErrorType.CALLBACK_ERROR, browser.getNavigationError()?.code)
  }

  /** Leaves [path] displayed with its page and no navigation error. */
  private fun TestScope.loadNavigation(browser: AudioBrowser, path: String) {
    browser.navigatePath(path)
    runCurrent()
    requests.removeAt(0).load(title = "First")
    runCurrent()
    assertEquals("First", browser.getContent()?.title)
    assertNull(browser.getNavigationError())
  }

  private fun TestScope.settleReload(settle: Request.() -> Unit) {
    runCurrent()
    requests.removeAt(0).settle()
    runCurrent()
  }

  @Test
  fun `a notifyContentChanged reload that loads clears an earlier navigation error`() =
    runTest(timeout = 5.seconds) {
      val browser = browser()
      failNavigation(browser, "/a")

      browser.notifyContentChanged("/a")
      settleReload { load() }

      assertEquals("Loaded", browser.getContent()?.title)
      assertNull(browser.getNavigationError())
      assertNull(browser.getFormattedNavigationError())
      assertNull(errorEvents.last())
      assertNull(formattedEvents.last())
    }

  @Test
  fun `an invalidateAllContent reload that loads clears an earlier navigation error`() =
    runTest(timeout = 5.seconds) {
      val browser = browser()
      failNavigation(browser, "/a")

      browser.invalidateAllContent()
      settleReload { load() }

      assertEquals("Loaded", browser.getContent()?.title)
      assertNull(browser.getNavigationError())
      assertNull(browser.getFormattedNavigationError())
      assertNull(errorEvents.last())
      assertNull(formattedEvents.last())
    }

  @Test
  fun `a notifyContentChanged reload that fails is reported as a navigation error`() =
    runTest(timeout = 5.seconds) {
      val browser = browser()
      loadNavigation(browser, "/a")

      browser.notifyContentChanged("/a")
      settleReload { fail() }

      assertEquals(NavigationErrorType.CALLBACK_ERROR, browser.getNavigationError()?.code)
      assertEquals(browser.getNavigationError(), errorEvents.last())
      assertEquals(browser.getFormattedNavigationError(), formattedEvents.last())
      assertEquals("First", browser.getContent()?.title)
    }

  @Test
  fun `an invalidateAllContent reload that fails is reported as a navigation error`() =
    runTest(timeout = 5.seconds) {
      val browser = browser()
      loadNavigation(browser, "/a")

      browser.invalidateAllContent()
      settleReload { fail() }

      assertEquals(NavigationErrorType.CALLBACK_ERROR, browser.getNavigationError()?.code)
      assertEquals(browser.getNavigationError(), errorEvents.last())
      assertEquals(browser.getFormattedNavigationError(), formattedEvents.last())
      assertEquals("First", browser.getContent()?.title)
    }

  @Test
  fun `a failed reload overtaken by a navigation reports nothing`() =
    runTest(timeout = 5.seconds) {
      val browser = browser()
      loadNavigation(browser, "/a")
      browser.notifyContentChanged("/a")
      runCurrent()
      browser.navigatePath("/b")
      runCurrent()

      requests.removeAt(0).fail()
      runCurrent()
      requests.removeAt(0).load()
      runCurrent()

      assertEquals("Loaded", browser.getContent()?.title)
      assertNull(browser.getNavigationError())
      assertEquals(emptyList<NavigationError?>(), errorEvents)
    }

  @Test
  fun `a reload overtaken by a navigation that fails leaves its navigation error`() =
    runTest(timeout = 5.seconds) {
      val browser = browser()
      loadNavigation(browser, "/a")
      browser.notifyContentChanged("/a")
      runCurrent()
      browser.navigatePath("/b")
      runCurrent()

      requests.removeAt(1).fail()
      runCurrent()
      requests.removeAt(0).load()
      runCurrent()

      assertNull(browser.getContent())
      assertEquals(NavigationErrorType.CALLBACK_ERROR, browser.getNavigationError()?.code)
    }
}
