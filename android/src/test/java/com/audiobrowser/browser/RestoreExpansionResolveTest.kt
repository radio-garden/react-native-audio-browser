package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.track
import com.audiobrowser.TestFixtures.transformableConfig
import com.audiobrowser.util.BrowserPathHelper
import com.margelo.nitro.audiobrowser.NativeRouteEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Offline, a restore tries each page once before the stored queue loads. */
@RunWith(RobolectricTestRunner::class)
class RestoreExpansionResolveTest {
  private val server = MockWebServer()
  private val requests = ConcurrentHashMap<String, AtomicInteger>()
  private val browserManager = BrowserManager()

  @Before
  fun setup() {
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val path = request.requestUrl?.encodedPath.orEmpty()
          requests.getOrPut(path) { AtomicInteger() }.incrementAndGet()
          return MockResponse().setResponseCode(500)
        }
      }
    server.start()
    browserManager.config =
      BrowserConfig(
        request = transformableConfig(baseUrl = server.url("/").toString().trimEnd('/')),
        routes = arrayOf(route("/p"), route("/linked"), route("/home")),
      )
  }

  @After fun tearDown() = server.shutdown()

  private fun route(path: String) =
    NativeRouteEntry(
      path = path,
      browseCallback = null,
      browseConfig = transformableConfig(),
      browseStatic = null,
      searchCallback = null,
      searchConfig = null,
      media = null,
      artwork = null,
    )

  private fun requestsTo(path: String) = requests[path]?.get() ?: 0

  @Test
  fun `a failing page is resolved once`() {
    val expansion = runBlocking {
      browserManager.expandQueueForRestore(
        BrowserPathHelper.build("/p", "a", 0),
        track(src = "a"),
        emptySet(),
      )
    }

    assertEquals(RestoreExpansion.Unavailable, expansion)
    assertEquals(1, requestsTo("/p"))
  }

  @Test
  fun `a failing linked page and its origin are resolved once each`() {
    val expansion = runBlocking {
      browserManager.expandQueueForRestore(
        BrowserPathHelper.buildLinked("/linked", "a", "/home", 0),
        track(src = "a"),
        emptySet(),
      )
    }

    assertEquals(RestoreExpansion.Unavailable, expansion)
    assertEquals(1, requestsTo("/linked"))
    assertEquals(1, requestsTo("/home"))
  }
}
