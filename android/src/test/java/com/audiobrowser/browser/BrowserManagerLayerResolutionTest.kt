package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Tests the request/browse layer resolution + caching introduced for resolver thunks.
 *
 * These tests exercise [BrowserManager.ensureLayersResolved]'s generation guard via the STATIC
 * layer path (`config.request` / `config.browse`, with the resolver thunks null). The guard is the
 * core of the per-content-generation caching: a static layer is resolved once and cached until the
 * generation bumps (config-set or [BrowserManager.clearContentCache], which runs from
 * `invalidateAllContent`), after which it is re-resolved.
 *
 * NOTE (gap): a test that proves a *resolver thunk* (`config.requestResolver`) is invoked exactly
 * once per generation is not included here. The resolver returns a Nitro `Promise<Variant<...>>`,
 * and constructing/awaiting a real Nitro `Promise` requires the JNI bridge (`Promise.initHybrid()`
 * / `nativeResolve` are `external`), which is unavailable in a JVM/Robolectric unit test. The
 * generation-guard logic those tests would assert (resolve-once-per-generation, re-resolve after a
 * bump, no in-flight cache) is identical for the static and resolver paths — `resolveLayer` only
 * differs in how it produces the `TransformableRequestConfig` — so the guard is fully covered here.
 * The resolver-await path is covered by the TS/web-stub tests and on iOS.
 */
class BrowserManagerLayerResolutionTest {

  private lateinit var browserManager: BrowserManager

  @Before
  fun setup() {
    browserManager = BrowserManager()
  }

  private fun staticConfig(path: String): TransformableRequestConfig =
    TransformableRequestConfig(
      transform = null,
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

    // First resolution: nothing cached yet (resolvedLayerGeneration starts at -1).
    val genAfterConfigSet = browserManager.layerGenerationForTest
    assertEquals(-1, browserManager.resolvedLayerGenerationForTest)

    browserManager.ensureLayersResolved()

    assertEquals(genAfterConfigSet, browserManager.resolvedLayerGenerationForTest)
    assertSame(request, browserManager.resolvedRequestLayerForTest)
    assertSame(browse, browserManager.resolvedBrowseLayerForTest)

    // Second call in the same generation is a no-op: the cached layers are untouched.
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

    // invalidateAllContent() routes through clearContentCache(): bump the generation.
    browserManager.clearContentCache()
    assertEquals(firstGen + 1, browserManager.layerGenerationForTest)
    // Cache is now stale: the resolved generation no longer matches.
    assertEquals(firstGen, browserManager.resolvedLayerGenerationForTest)

    // Swap the underlying config (the resolver/static layer can change between generations)
    // WITHOUT bumping generation again via the setter — emulate the resolver returning a new value
    // by re-resolving against a fresh config set, which itself bumps once.
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
}
