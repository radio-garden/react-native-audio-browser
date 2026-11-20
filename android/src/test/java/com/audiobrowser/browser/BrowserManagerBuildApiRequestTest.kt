package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.throwingTransform
import com.audiobrowser.TestFixtures.transformableConfig
import com.audiobrowser.navigationErrorFor
import com.margelo.nitro.audiobrowser.NavigationErrorType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class BrowserManagerBuildApiRequestTest {

  private lateinit var bm: BrowserManager

  @Before
  fun setup() {
    bm = BrowserManager()
  }

  @Test
  fun `layers apply request then kind then route, override-wins`() = runTest {
    bm.config =
      BrowserConfig(
        request =
          transformableConfig(baseUrl = "https://api.example.com", query = mapOf("a" to "request"))
      )
    val request =
      bm.buildApiRequest(
        kindConfig = transformableConfig(query = mapOf("a" to "kind", "b" to "kind")),
        routeConfig = transformableConfig(query = mapOf("b" to "route")),
        path = "/stations",
        params = emptyMap(),
      )
    assertTrue(request.url, request.url.startsWith("https://api.example.com/stations?"))
    assertTrue(request.url, request.url.contains("a=kind"))
    assertTrue(request.url, request.url.contains("b=route"))
  }

  @Test
  fun `initialQuery seeds the base so it reaches the final URL`() = runTest {
    bm.config = BrowserConfig(request = transformableConfig(baseUrl = "https://api.example.com"))
    val request =
      bm.buildApiRequest(
        kindConfig = transformableConfig(query = mapOf("extra" to "1")),
        routeConfig = null,
        path = "/search",
        params = emptyMap(),
        initialQuery = mapOf("q" to "jazz", "mode" to "genre"),
      )
    assertTrue(request.url, request.url.contains("q=jazz"))
    assertTrue(request.url, request.url.contains("mode=genre"))
    assertTrue(request.url, request.url.startsWith("https://api.example.com/search?"))
  }

  @Test
  fun `a layer's static path does not override the base path`() = runTest {
    bm.config = BrowserConfig(request = transformableConfig(baseUrl = "https://api.example.com"))
    val request =
      bm.buildApiRequest(
        kindConfig = transformableConfig(path = "/kind-path"),
        routeConfig = transformableConfig(path = "/route-path"),
        path = "/stations",
        params = emptyMap(),
      )
    assertTrue(request.url, request.url.startsWith("https://api.example.com/stations"))
  }

  @Test
  fun `headers merge across layers with later layers winning`() = runTest {
    bm.config =
      BrowserConfig(
        request =
          transformableConfig(
            baseUrl = "https://api.example.com",
            headers = mapOf("x-a" to "request", "x-b" to "request"),
          )
      )
    val request =
      bm.buildApiRequest(
        kindConfig = transformableConfig(headers = mapOf("x-b" to "kind")),
        routeConfig = null,
        path = "/p",
        params = emptyMap(),
      )
    assertEquals("request", request.headers?.get("x-a"))
    assertEquals("kind", request.headers?.get("x-b"))
  }

  @Test
  fun `missing baseUrl throws ContentNotFoundException`() = runTest {
    bm.config = BrowserConfig(request = transformableConfig(query = mapOf("a" to "1")))
    try {
      bm.buildApiRequest(
        kindConfig = null,
        routeConfig = null,
        path = "/nowhere",
        params = emptyMap(),
      )
      fail("expected ContentNotFoundException")
    } catch (e: ContentNotFoundException) {
      assertEquals("/nowhere", e.path)
    }
  }

  @Test
  fun `a route transform that throws fails with callback-error`() = runTest {
    bm.config = BrowserConfig(request = transformableConfig(baseUrl = "https://api.example.com"))
    try {
      bm.buildApiRequest(
        kindConfig = null,
        routeConfig = transformableConfig().copy(transform = throwingTransform("token expired")),
        path = "/library",
        params = emptyMap(),
      )
      fail("expected CallbackException")
    } catch (e: CallbackException) {
      val error = navigationErrorFor(e)
      assertEquals(NavigationErrorType.CALLBACK_ERROR, error.code)
      assertEquals("token expired", error.message)
    }
  }
}
