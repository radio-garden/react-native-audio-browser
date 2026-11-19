package com.audiobrowser.browser

import com.audiobrowser.util.FALLBACK_TIMEOUT
import com.audiobrowser.util.JsPromise
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import com.margelo.nitro.core.Promise
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Covers the generation guard, through static layers, and the viewMoreTitle callback. */
// Robolectric provides the android.util.LruCache whose evictAll() clearContentCache calls.
@RunWith(RobolectricTestRunner::class)
class BrowserManagerLayerResolutionTest {

  private lateinit var browserManager: BrowserManager

  @Before
  fun setup() {
    browserManager = BrowserManager()
  }

  private fun staticConfig(path: String): TransformableRequestConfig =
    TransformableRequestConfig(
      transform = null,
      transformSync = null,
      method = null,
      path = path,
      baseUrl = null,
      headers = null,
      query = null,
      body = null,
      contentType = null,
      userAgent = null,
    )

  @Test
  fun `ensureLayersResolved caches static layers and is a no-op within a generation`() = runTest {
    val request = staticConfig("/request")
    val browse = staticConfig("/browse")
    browserManager.config = BrowserConfig(request = request, browse = browse)

    // -1: never resolved.
    val genAfterConfigSet = browserManager.layerGenerationForTest
    assertEquals(-1, browserManager.resolvedLayerGenerationForTest)

    browserManager.ensureLayersResolved()

    assertEquals(genAfterConfigSet, browserManager.resolvedLayerGenerationForTest)
    assertSame(request, browserManager.resolvedRequestLayerForTest)
    assertSame(browse, browserManager.resolvedBrowseLayerForTest)

    browserManager.ensureLayersResolved()
    assertEquals(genAfterConfigSet, browserManager.resolvedLayerGenerationForTest)
    assertSame(request, browserManager.resolvedRequestLayerForTest)
  }

  @Test
  fun `clearContentCache bumps the generation so layers re-resolve`() = runTest {
    val request1 = staticConfig("/v1")
    browserManager.config = BrowserConfig(request = request1)
    browserManager.ensureLayersResolved()
    val firstGen = browserManager.resolvedLayerGenerationForTest
    assertSame(request1, browserManager.resolvedRequestLayerForTest)

    browserManager.clearContentCache()
    assertEquals(firstGen + 1, browserManager.layerGenerationForTest)
    assertEquals(firstGen, browserManager.resolvedLayerGenerationForTest)

    val request2 = staticConfig("/v2")
    val genBeforeSet = browserManager.layerGenerationForTest
    browserManager.config = BrowserConfig(request = request2)
    assertEquals(genBeforeSet + 1, browserManager.layerGenerationForTest)

    browserManager.ensureLayersResolved()
    assertEquals(
      browserManager.layerGenerationForTest,
      browserManager.resolvedLayerGenerationForTest,
    )
    assertSame(request2, browserManager.resolvedRequestLayerForTest)
  }

  @Test
  fun `setting config bumps the generation each time`() = runTest {
    val gen0 = browserManager.layerGenerationForTest
    browserManager.config = BrowserConfig(request = staticConfig("/a"))
    val gen1 = browserManager.layerGenerationForTest
    browserManager.config = BrowserConfig(request = staticConfig("/b"))
    val gen2 = browserManager.layerGenerationForTest

    assertEquals(gen0 + 1, gen1)
    assertEquals(gen1 + 1, gen2)
  }

  @Test
  fun `ensureLayersResolved with no layers caches nulls but marks the generation resolved`() =
    runTest {
      browserManager.config = BrowserConfig()
      browserManager.ensureLayersResolved()

      assertNull(browserManager.resolvedRequestLayerForTest)
      assertNull(browserManager.resolvedBrowseLayerForTest)
      assertEquals(
        browserManager.layerGenerationForTest,
        browserManager.resolvedLayerGenerationForTest,
      )
    }

  @Test
  fun `only the request layer is configured`() = runTest {
    val request = staticConfig("/only-request")
    browserManager.config = BrowserConfig(request = request)
    browserManager.ensureLayersResolved()

    assertNotNull(browserManager.resolvedRequestLayerForTest)
    assertSame(request, browserManager.resolvedRequestLayerForTest)
    assertNull(browserManager.resolvedBrowseLayerForTest)
  }

  @Test
  fun `viewMoreTitle falls back to the in-code default when unconfigured`() = runTest {
    browserManager.config = BrowserConfig()

    assertEquals(BrowserManager.DEFAULT_VIEW_MORE_TITLE, browserManager.viewMoreTitle())
  }

  /** A viewMoreTitle callback answering from the next of [promises], counting its calls. */
  private class TitleCallback(vararg promises: JsPromise<String>) {
    private val queue = ArrayDeque(promises.toList())
    var calls = 0
    val callback: () -> Promise<String> = {
      calls += 1
      queue.removeFirst().promise
    }
  }

  @Test
  fun `a viewMoreTitle answering inside the timeout is kept for the generation`() =
    runTest(timeout = 5.seconds) {
      val js = JsPromise<String>()
      val title = TitleCallback(js)
      browserManager.config = BrowserConfig(viewMoreTitle = title.callback)
      val first = backgroundScope.async { browserManager.viewMoreTitle() }
      runCurrent()

      advanceTimeBy(FALLBACK_TIMEOUT - 1.seconds)
      js.resolve("More")
      runCurrent()

      assertTrue(first.isCompleted)
      assertEquals("More", first.await())
      assertEquals("More", browserManager.viewMoreTitle())
      assertEquals(1, title.calls)
    }

  @Test
  fun `a viewMoreTitle that times out falls back for the generation`() =
    runTest(timeout = 5.seconds) {
      val title = TitleCallback(JsPromise())
      browserManager.config = BrowserConfig(viewMoreTitle = title.callback)
      val first = backgroundScope.async { browserManager.viewMoreTitle() }

      advanceTimeBy(FALLBACK_TIMEOUT - 1.seconds)
      runCurrent()
      assertFalse(first.isCompleted)
      advanceTimeBy(2.seconds)
      runCurrent()

      assertTrue(first.isCompleted)
      assertEquals(BrowserManager.DEFAULT_VIEW_MORE_TITLE, first.await())
      assertEquals(BrowserManager.DEFAULT_VIEW_MORE_TITLE, browserManager.viewMoreTitle())
      assertEquals(1, title.calls)
    }

  @Test
  fun `a viewMoreTitle whose caller is cancelled caches nothing`() =
    runTest(timeout = 5.seconds) {
      val silent = JsPromise<String>()
      val next = JsPromise<String>().apply { resolve("More") }
      val title = TitleCallback(silent, next)
      browserManager.config = BrowserConfig(viewMoreTitle = title.callback)
      val first = backgroundScope.async { browserManager.viewMoreTitle() }
      runCurrent()

      first.cancel()
      runCurrent()

      assertTrue(first.isCompleted)
      assertEquals("More", browserManager.viewMoreTitle())
    }
}
