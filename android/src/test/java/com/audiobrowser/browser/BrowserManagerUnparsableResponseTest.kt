package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.transformableConfig
import com.audiobrowser.navigationErrorFor
import com.margelo.nitro.audiobrowser.NavigationError
import com.margelo.nitro.audiobrowser.NavigationErrorType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A page fetch answered 2xx with a body that is not a page. */
@RunWith(RobolectricTestRunner::class)
class BrowserManagerUnparsableResponseTest {

  private lateinit var server: MockWebServer
  private lateinit var browserManager: BrowserManager

  @Before
  fun setup() {
    server = MockWebServer()
    server.start()
    browserManager = BrowserManager()
    browserManager.config =
      BrowserConfig(
        request = transformableConfig(baseUrl = server.url("/").toString().trimEnd('/'))
      )
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun navigationErrorOfPage(response: MockResponse): NavigationError {
    server.enqueue(response)
    try {
      runBlocking { browserManager.resolve("/page") }
    } catch (e: Exception) {
      return navigationErrorFor(e)
    }
    fail("resolve returned a page")
    throw AssertionError()
  }

  private fun assertSuccessfulHttpError(statusCode: Int, error: NavigationError) {
    assertEquals(NavigationErrorType.HTTP_ERROR, error.code)
    assertEquals(statusCode.toDouble(), error.statusCode!!, 0.0)
    assertEquals(true, error.statusCodeSuccess)
  }

  @Test
  fun `a 2xx body that is not JSON is an http-error with a successful status`() {
    val error = navigationErrorOfPage(MockResponse().setBody("<html>Sign in</html>"))

    assertSuccessfulHttpError(200, error)
  }

  @Test
  fun `a 2xx JSON body that is not a page is an http-error with a successful status`() {
    val error =
      navigationErrorOfPage(MockResponse().setResponseCode(203).setBody("""{"path":"/page"}"""))

    assertSuccessfulHttpError(203, error)
  }

  @Test
  fun `a 2xx page with artwork of the wrong shape is an http-error with a successful status`() {
    val error =
      navigationErrorOfPage(
        MockResponse().setBody("""{"path":"/page","title":"Page","artwork":{"light":"l.png"}}""")
      )

    assertSuccessfulHttpError(200, error)
  }
}
