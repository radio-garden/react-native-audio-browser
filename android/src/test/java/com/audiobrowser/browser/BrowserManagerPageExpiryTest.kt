package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.transformableConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

  @Before
  fun setup() {
    server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val count = requests.incrementAndGet()
          val response = MockResponse().setBody("""{"path":"/page","title":"$count"}""")
          cacheControl.forEach { response.addHeader("Cache-Control", it) }
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
    BrowserManager(pageTimers = backgroundScope, now = { testScheduler.currentTime }).apply {
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
  fun `a page with a positive max-age expires and is refetched`() = runTest {
    cacheControl = listOf("max-age=60")
    val expired = mutableListOf<String>()
    val browserManager = browserManager(expired)
    browserManager.resolve("/page")

    advance(61_000)

    assertEquals(listOf("/page"), expired)
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
