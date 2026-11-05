package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the generation guard through static layers only. A resolver thunk or viewMoreTitle
 * callback returns a Nitro Promise, which needs the JNI bridge a JVM test lacks; the guard is the
 * same for both paths.
 */
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
}
