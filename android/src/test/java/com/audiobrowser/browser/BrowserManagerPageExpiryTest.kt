package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.transformableConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BrowserManagerPageExpiryTest {
  private lateinit var server: MockWebServer
  private val requests = AtomicInteger()
  @Volatile private var cacheControl: List<String> = emptyList()
  @Volatile private var status = 200
  @Volatile private var age: String? = null

  @Before
  fun setup() {
    server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val count = requests.incrementAndGet()
          val response =
            MockResponse().setResponseCode(status).setBody("""{"path":"/page","title":"$count"}""")
          cacheControl.forEach { response.addHeader("Cache-Control", it) }
          age?.let { response.addHeader("Age", it) }
          return response
        }
      }
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun TestScope.browserManager(expired: MutableList<String>) =
    BrowserManager(
        pageContext = StandardTestDispatcher(testScheduler),
        now = { testScheduler.currentTime },
      )
      .apply {
        config =
          BrowserConfig(
            request = transformableConfig(baseUrl = server.url("/").toString().trimEnd('/'))
          )
        setOnPageExpired { expired += it }
      }

  private fun TestScope.advance(ms: Long) {
    advanceTimeBy(ms)
    runCurrent()
  }

  @Test
  fun `a page with a positive max-age expires and is refetched behind the stale page`() = runTest {
    cacheControl = listOf("max-age=60")
    val expired = mutableListOf<String>()
    val refreshed = CompletableDeferred<String>()
    val browserManager = browserManager(expired)
    browserManager.setOnPageRefreshed { refreshed.complete(it) }
    browserManager.resolve("/page")

    advance(61_000)

    assertEquals(listOf("/page"), expired)
    assertEquals("1", browserManager.resolve("/page").title)
    assertEquals("/page", refreshed.await())
    assertEquals("2", browserManager.resolve("/page").title)
  }

  @Test
  fun `an undecodable body or a request-layer error is judged as such`() {
    assertEquals(false, keepsStalePage(HttpStatusException(200, "not a page")))
    assertEquals(true, keepsStalePage(NetworkException("offline")))
    assertEquals(true, keepsStalePage(IllegalStateException("transform threw")))
  }

  @Test
  fun `a refetch answered already expired makes one request and is kept for the floor`() = runTest {
    cacheControl = listOf("max-age=60")
    age = "90"
    val refreshed = CompletableDeferred<String>()
    val browserManager = browserManager(mutableListOf())
    browserManager.setOnPageRefreshed { refreshed.complete(it) }
    browserManager.resolve("/page")
    advance(PageCache.LIFETIME_FLOOR_MS + 1_000)

    assertEquals("1", browserManager.resolve("/page").title)
    refreshed.await()
    assertEquals("2", browserManager.resolve("/page").title)
    assertEquals("2", browserManager.resolve("/page").title)

    assertEquals(2, requests.get())
  }

  @Test
  fun `a failed refetch keeps the stale page unless the server refused it`() = runTest {
    cacheControl = listOf("max-age=60")
    for ((code, kept) in
      listOf(
        408 to true,
        429 to true,
        500 to true,
        503 to true,
        400 to false,
        404 to false,
        410 to false,
      )) {
      status = 200
      val browserManager = browserManager(mutableListOf())
      val title = browserManager.resolve("/page").title
      advance(61_000)

      status = code
      browserManager.refreshPage("/page")

      val after = runCatching { browserManager.resolve("/page").title }.getOrNull()
      assertEquals("$code", if (kept) title else null, after)
    }
  }

  @Test
  fun `a refresh after dispose returns at once`() = runTest {
    cacheControl = listOf("max-age=60")
    val browserManager = browserManager(mutableListOf())
    browserManager.resolve("/page")
    advance(61_000)

    browserManager.dispose()
    browserManager.refreshPage("/page")
  }

  @Test
  fun `a disposed manager pushes no page`() = runTest {
    cacheControl = listOf("max-age=60")
    val expired = mutableListOf<String>()
    val browserManager = browserManager(expired)
    browserManager.resolve("/page")

    browserManager.dispose()
    advance(61_000)

    assertEquals(emptyList<String>(), expired)
  }

  @Test
  fun `a refresh refetches a page that has not expired`() = runTest {
    cacheControl = listOf("max-age=60")
    val browserManager = browserManager(mutableListOf())
    browserManager.navigate("/page")

    browserManager.refresh()

    assertEquals("2", browserManager.resolve("/page").title)
  }

  @Test
  fun `a max-age on one of several Cache-Control lines counts`() = runTest {
    cacheControl = listOf("max-age=60", "public")
    val expired = mutableListOf<String>()
    browserManager(expired).resolve("/page")

    advance(61_000)

    assertEquals(listOf("/page"), expired)
  }

  @Test
  fun `a page with any other Cache-Control is cached with no expiry`() = runTest {
    for (header in listOf(null, "no-store", "no-cache", "max-age=0, private, must-revalidate")) {
      cacheControl = listOfNotNull(header)
      requests.set(0)
      val expired = mutableListOf<String>()
      val browserManager = browserManager(expired)
      browserManager.resolve("/page")

      advance(1_000_000)

      assertEquals("$header", emptyList<String>(), expired)
      assertEquals("$header", "1", browserManager.resolve("/page").title)
    }
  }
}
