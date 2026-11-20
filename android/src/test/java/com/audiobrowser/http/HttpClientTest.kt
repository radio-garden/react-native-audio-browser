package com.audiobrowser.http

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpClientTest {

  /** Every User-Agent line a GET with [headers] and the default `userAgent` carries. */
  private fun sentUserAgents(headers: Map<String, String>): List<String> =
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("{}"))
      runTest {
        HttpClient()
          .request(HttpClient.HttpRequest(url = server.url("/").toString(), headers = headers))
          .getOrThrow()
      }
      server.takeRequest().headers.values("User-Agent")
    }

  @Test
  fun `a user-agent header in any case is sent alone, in place of the default`() {
    for (name in listOf("User-Agent", "user-agent", "USER-AGENT")) {
      assertEquals(name, listOf("MyApp/1.0"), sentUserAgents(mapOf(name to "MyApp/1.0")))
    }
  }

  @Test
  fun `without a user-agent header the default is sent`() {
    assertEquals(listOf(HttpClient.DEFAULT_USER_AGENT), sentUserAgents(emptyMap()))
  }
}
