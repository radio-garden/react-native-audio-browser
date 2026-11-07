package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.transformableConfig
import com.margelo.nitro.audiobrowser.NativeRouteEntry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * clearContentCache (invalidateAllContent) against an HTTP backend whose titles are in the current
 * [language]. A held request reads the language when it arrives, so it answers in the one before
 * the clear.
 */
@RunWith(RobolectricTestRunner::class)
class BrowserManagerInvalidationTest {

  private lateinit var server: MockWebServer
  private lateinit var browserManager: BrowserManager

  @Volatile private var language = "en"
  @Volatile private var hold: CountDownLatch? = null
  @Volatile private var failing = false
  private val arrived = CountDownLatch(1)

  @Before
  fun setup() {
    server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val title = language
          hold?.let {
            arrived.countDown()
            it.await(5, TimeUnit.SECONDS)
          }
          if (failing) return MockResponse().setResponseCode(500)
          val path = request.path.orEmpty()
          val body =
            when {
              path.startsWith("/search") -> """[{"title":"$title","src":"https://s/a.mp3"}]"""
              path.contains("tabs") ->
                """{"path":"/tabs","title":"Tabs","children":[{"title":"$title","path":"/t"}]}"""
              else -> """{"path":"/page","title":"$title"}"""
            }
          return MockResponse().setBody(body)
        }
      }
    server.start()
    browserManager = BrowserManager()
    browserManager.config = config()
  }

  private fun config() =
    BrowserConfig(
      request = transformableConfig(baseUrl = server.url("/").toString().trimEnd('/')),
      routes =
        arrayOf(
          apiRoute(BrowserManager.TABS_ROUTE_PATH, browse = true),
          apiRoute(BrowserManager.SEARCH_ROUTE_PATH, browse = false),
        ),
    )

  @After
  fun tearDown() {
    hold?.countDown()
    server.shutdown()
  }

  private fun apiRoute(path: String, browse: Boolean) =
    NativeRouteEntry(
      path = path,
      browseCallback = null,
      browseConfig = if (browse) transformableConfig() else null,
      browseStatic = null,
      searchCallback = null,
      searchConfig = if (browse) null else transformableConfig(path = "/search"),
      media = null,
      artwork = null,
    )

  /** Runs [request] with the server holding its response across [meanwhile]. */
  private fun <T> across(
    meanwhile: () -> Unit = { browserManager.clearContentCache() },
    request: suspend () -> T,
  ): Unit = runBlocking {
    val release = CountDownLatch(1)
    hold = release
    val inFlight = async(Dispatchers.Default) { request() }
    assertTrue(arrived.await(5, TimeUnit.SECONDS))
    language = "nl"
    meanwhile()
    hold = null
    release.countDown()
    inFlight.await()
  }

  @Test
  fun `a page in flight across clearContentCache is not cached`() {
    across { browserManager.resolve("/page") }

    assertEquals("nl", runBlocking { browserManager.resolve("/page") }.title)
  }

  @Test
  fun `a search in flight across clearContentCache is not kept`() {
    across { browserManager.search("jazz") }

    assertNull(browserManager.getCachedSearchResults("jazz"))
  }

  @Test
  fun `clearContentCache re-resolves the tabs`() = runBlocking {
    assertEquals("en", browserManager.queryTabs().single().title)

    language = "nl"
    browserManager.clearContentCache()

    assertEquals("nl", browserManager.queryTabs().single().title)
  }

  @Test
  fun `tabs in flight across clearContentCache are not kept`() {
    across { browserManager.queryTabs() }

    assertNull(browserManager.getTabs())
  }

  @Test
  fun `a page in flight across a config change is cached`() {
    across({ browserManager.config = config() }) { browserManager.resolve("/page") }

    assertEquals("en", runBlocking { browserManager.resolve("/page") }.title)
  }

  @Test
  fun `a failed tabs re-query keeps the previous tabs`() = runBlocking {
    browserManager.queryTabs()
    browserManager.clearContentCache()
    failing = true

    assertTrue(runCatching { browserManager.queryTabs() }.isFailure)
    assertEquals("en", browserManager.getTabs()?.single()?.title)
  }
}
