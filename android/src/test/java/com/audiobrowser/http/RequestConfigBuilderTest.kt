package com.audiobrowser.http

import com.audiobrowser.TestFixtures
import com.audiobrowser.TestFixtures.mediaConfig
import com.audiobrowser.TestFixtures.throwingTransform
import com.audiobrowser.TestFixtures.throwingTransformSync
import com.audiobrowser.TestFixtures.transformableConfig
import com.audiobrowser.browser.CallbackException
import com.audiobrowser.util.JsPromise
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__shared_ptr_Promise_RequestConfig_____RequestConfig_std__optional_std__unordered_map_std__string__std__string__
import com.margelo.nitro.audiobrowser.HttpMethod
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.core.Promise
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RequestConfigBuilderTest {

  @Test
  fun `buildHttpRequest should use defaults when config fields are null`() = runTest {
    val config =
      RequestConfig(
        method = null,
        path = null,
        baseUrl = null,
        headers = null,
        query = null,
        body = null,
        contentType = null,
        userAgent = null,
      )

    val result = RequestConfigBuilder.buildHttpRequest(config)

    assertEquals("GET", result.method)
    assertEquals("", result.url)
    assertEquals(HttpClient.DEFAULT_USER_AGENT, result.userAgent)
    assertEquals(HttpClient.DEFAULT_CONTENT_TYPE, result.contentType)
    assertNull(result.body)
  }

  @Test
  fun `buildHttpRequest should build correct URL with all components`() = runTest {
    val config =
      RequestConfig(
        method = HttpMethod.GET,
        path = "/albums",
        baseUrl = "https://api.example.com",
        headers = mapOf("Authorization" to "Bearer token"),
        query = mapOf("limit" to "20", "offset" to "0"),
        body = null,
        contentType = "application/json",
        userAgent = "MyApp/1.0",
      )

    val result = RequestConfigBuilder.buildHttpRequest(config)

    assertEquals("GET", result.method)
    assertEquals("https://api.example.com/albums?limit=20&offset=0", result.url)
    assertEquals("Bearer token", result.headers?.get("Authorization"))
    assertEquals("MyApp/1.0", result.userAgent)
    assertEquals("application/json", result.contentType)
  }

  @Test
  fun `buildHttpRequest should handle POST with body`() = runTest {
    val config =
      RequestConfig(
        method = HttpMethod.POST,
        path = "/users",
        baseUrl = "https://api.example.com",
        headers = null,
        query = null,
        body = """{"name":"John"}""",
        contentType = "application/json",
        userAgent = null,
      )

    val result = RequestConfigBuilder.buildHttpRequest(config)

    assertEquals("POST", result.method)
    assertEquals("""{"name":"John"}""", result.body)
    assertEquals("application/json", result.contentType)
  }

  @Test
  fun `mergeConfig should override base with specific values`() {
    val base =
      RequestConfig(
        method = HttpMethod.GET,
        path = "/base",
        baseUrl = "https://base.com",
        headers = mapOf("Base" to "value"),
        query = mapOf("base" to "param"),
        body = null,
        contentType = "text/plain",
        userAgent = "BaseAgent",
      )

    val override =
      RequestConfig(
        method = HttpMethod.POST,
        path = "/override",
        baseUrl = "https://override.com",
        headers = mapOf("Override" to "value"),
        query = mapOf("override" to "param"),
        body = "body",
        contentType = "application/json",
        userAgent = "OverrideAgent",
      )

    val result = RequestConfigBuilder.mergeConfig(base, override)

    assertEquals(HttpMethod.POST, result.method)
    assertEquals("/override", result.path)
    assertEquals("https://override.com", result.baseUrl)
    assertEquals("body", result.body)
    assertEquals("application/json", result.contentType)
    assertEquals("OverrideAgent", result.userAgent)
  }

  @Test
  fun `mergeConfig should preserve base values when override is null`() {
    val base =
      RequestConfig(
        method = HttpMethod.PUT,
        path = "/base",
        baseUrl = "https://base.com",
        headers = mapOf("Base" to "value"),
        query = mapOf("base" to "param"),
        body = "base-body",
        contentType = "text/plain",
        userAgent = "BaseAgent",
      )

    val override =
      RequestConfig(
        method = null,
        path = null,
        baseUrl = null,
        headers = null,
        query = null,
        body = null,
        contentType = null,
        userAgent = null,
      )

    val result = RequestConfigBuilder.mergeConfig(base, override)

    assertEquals(HttpMethod.PUT, result.method)
    assertEquals("/base", result.path)
    assertEquals("https://base.com", result.baseUrl)
    assertEquals("base-body", result.body)
    assertEquals("text/plain", result.contentType)
    assertEquals("BaseAgent", result.userAgent)
  }

  @Test
  fun `mergeConfig should merge headers and query parameters`() {
    val base =
      RequestConfig(
        method = null,
        path = null,
        baseUrl = null,
        headers = mapOf("Base-Header" to "base-value", "Shared" to "base-shared"),
        query = mapOf("base-query" to "base-value", "shared" to "base-shared"),
        body = null,
        contentType = null,
        userAgent = null,
      )

    val override =
      RequestConfig(
        method = null,
        path = null,
        baseUrl = null,
        headers = mapOf("Override-Header" to "override-value", "Shared" to "override-shared"),
        query = mapOf("override-query" to "override-value", "shared" to "override-shared"),
        body = null,
        contentType = null,
        userAgent = null,
      )

    val result = RequestConfigBuilder.mergeConfig(base, override)

    assertEquals("base-value", result.headers!!["Base-Header"])
    assertEquals("override-value", result.headers!!["Override-Header"])
    assertEquals("override-shared", result.headers!!["Shared"])

    assertEquals("base-value", result.query!!["base-query"])
    assertEquals("override-value", result.query!!["override-query"])
    assertEquals("override-shared", result.query!!["shared"])
  }

  @Test
  fun `buildUrl should handle path normalization`() = runTest {
    val configWithSlash =
      RequestConfig(
        method = null,
        path = "/albums",
        baseUrl = "https://api.example.com",
        headers = null,
        query = null,
        body = null,
        contentType = null,
        userAgent = null,
      )

    val configWithoutSlash =
      RequestConfig(
        method = null,
        path = "albums",
        baseUrl = "https://api.example.com",
        headers = null,
        query = null,
        body = null,
        contentType = null,
        userAgent = null,
      )

    val result1 = RequestConfigBuilder.buildHttpRequest(configWithSlash)
    val result2 = RequestConfigBuilder.buildHttpRequest(configWithoutSlash)

    assertEquals("https://api.example.com/albums", result1.url)
    assertEquals("https://api.example.com/albums", result2.url)
  }

  @Test
  fun `buildUrl should handle empty query parameters`() = runTest {
    val config =
      RequestConfig(
        method = null,
        path = "/albums",
        baseUrl = "https://api.example.com",
        headers = null,
        query = emptyMap(),
        body = null,
        contentType = null,
        userAgent = null,
      )

    val result = RequestConfigBuilder.buildHttpRequest(config)

    assertEquals("https://api.example.com/albums", result.url)
  }

  @Test
  fun `buildUrl leaves the query off a data URL`() {
    val dataUrl = "data:image/png;base64,iVBORw0KGgo="
    val config =
      RequestConfig(
        method = null,
        path = dataUrl,
        baseUrl = "https://api.example.com",
        headers = null,
        query = mapOf("w" to "512"),
        body = null,
        contentType = null,
        userAgent = null,
      )

    assertEquals(dataUrl, RequestConfigBuilder.buildUrl(config))
  }

  @Test
  fun `buildUrl joins the query with an ampersand when the path already has one`() = runTest {
    val relative =
      RequestConfig(
        method = null,
        path = "/stations?genre=rock",
        baseUrl = "https://api.example.com",
        headers = null,
        query = mapOf("locale" to "en"),
        body = null,
        contentType = null,
        userAgent = null,
      )
    val absolute =
      relative.copy(path = "https://img.example.com/a.jpg?v=2", query = mapOf("w" to "512"))

    assertEquals(
      "https://api.example.com/stations?genre=rock&locale=en",
      RequestConfigBuilder.buildHttpRequest(relative).url,
    )
    assertEquals(
      "https://img.example.com/a.jpg?v=2&w=512",
      RequestConfigBuilder.buildHttpRequest(absolute).url,
    )
  }

  @Test
  fun `composeResolved merges async then sync with sync winning`() {
    val async =
      RequestConfig(null, null, "https://async.example.com", null, null, null, null, "ASYNC")
    val sync = RequestConfig(null, null, null, null, null, null, null, "SYNC")

    val merged = RequestConfigBuilder.composeResolved(async, sync)

    assertEquals("SYNC", merged?.userAgent)
    assertEquals("https://async.example.com", merged?.baseUrl)
  }

  @Test
  fun `composeResolved returns the single present side, or null for neither`() {
    val cfg = RequestConfig(null, null, null, null, null, null, null, "ONLY")

    assertEquals("ONLY", RequestConfigBuilder.composeResolved(cfg, null)?.userAgent)
    assertEquals("ONLY", RequestConfigBuilder.composeResolved(null, cfg)?.userAgent)
    assertNull(RequestConfigBuilder.composeResolved(null, null))
  }

  @Test
  fun `mergeConfigOrBase MediaRequestConfig static fields merge over base and preserve callbacks`() =
    runTest {
      val base =
        RequestConfig(
          method = null,
          path = "/stream.mp3",
          baseUrl = "https://media.example.com",
          headers = mapOf("x-base" to "1"),
          query = null,
          body = null,
          contentType = null,
          userAgent = "base-ua",
        )
      val override = mediaConfig(headers = mapOf("x-media" to "2"), query = mapOf("token" to "abc"))
      val merged = RequestConfigBuilder.mergeConfigOrBase(base, override)
      assertEquals("/stream.mp3", merged.path)
      assertEquals("https://media.example.com", merged.baseUrl)
      assertEquals("1", merged.headers?.get("x-base"))
      assertEquals("2", merged.headers?.get("x-media"))
      assertEquals("abc", merged.query?.get("token"))
      assertEquals("base-ua", merged.userAgent)
    }

  @Test
  fun `mergeConfig TransformableRequestConfig carries the base path over a static layer path`() =
    runTest {
      val base =
        RequestConfig(null, "/navigated", "https://api.example.com", null, null, null, null, null)
      val layer = transformableConfig(path = "/static-layer-path")
      val merged = RequestConfigBuilder.mergeConfig(base, layer, emptyMap())
      assertEquals("/navigated", merged.path)
    }

  @Test
  fun `mergeConfigOrBase MediaRequestConfig carries the base path over a static media path`() =
    runTest {
      val base =
        RequestConfig(null, "/stream/123", "https://api.example.com", null, null, null, null, null)
      val media = mediaConfig(path = "/static-media-path")
      val merged = RequestConfigBuilder.mergeConfigOrBase(base, media)
      assertEquals("/stream/123", merged.path)
    }

  @Test
  fun `mergeConfig TransformableRequestConfig throws a CallbackException when a transform throws`() =
    runTest {
      val base =
        RequestConfig(null, "/library", "https://api.example.com", null, null, null, null, null)
      val layer = transformableConfig().copy(transform = throwingTransform("token expired"))
      try {
        RequestConfigBuilder.mergeConfig(base, layer, emptyMap())
        fail("expected CallbackException")
      } catch (e: CallbackException) {
        assertEquals("token expired", e.message)
      }
    }

  @Test
  fun `a throwing transformSync's CallbackException keeps its message whole`() = runTest {
    val base =
      RequestConfig(null, "/library", "https://api.example.com", null, null, null, null, null)
    val message = "Error: token expired\n\nsign in again"
    val layer = transformableConfig().copy(transformSync = throwingTransformSync(message))
    try {
      RequestConfigBuilder.mergeConfig(base, layer, emptyMap())
      fail("expected CallbackException")
    } catch (e: CallbackException) {
      assertEquals(message, e.message)
    }
  }

  @Test
  fun `a transform rejecting with a TypeError gives its message alone`() = runTest {
    val base =
      RequestConfig(null, "/library", "https://api.example.com", null, null, null, null, null)
    val layer =
      transformableConfig().copy(transform = throwingTransform("bad header", name = "TypeError"))
    try {
      RequestConfigBuilder.mergeConfig(base, layer, emptyMap())
      fail("expected CallbackException")
    } catch (e: CallbackException) {
      assertEquals("bad header", e.message)
    }
  }

  @Test
  fun `mergeConfigOrBase skips a layer whose transform throws`() = runTest {
    val base =
      RequestConfig(null, "/stream.mp3", "https://media.example.com", null, null, null, null, null)
    val layer = transformableConfig().copy(transform = throwingTransform("token expired"))
    assertEquals(base, RequestConfigBuilder.mergeConfigOrBase(base, layer))
  }

  @Test
  fun `mergeConfigOrBase MediaRequestConfig skips a layer whose transform throws`() = runTest {
    val base =
      RequestConfig(null, "/stream.mp3", "https://media.example.com", null, null, null, null, null)
    val media = mediaConfig().copy(transform = throwingTransform("token expired"))
    val merged = RequestConfigBuilder.mergeConfigOrBase(base, media)
    assertEquals("https://media.example.com", merged.baseUrl)
    assertEquals("/stream.mp3", merged.path)
  }

  @Test
  fun `a transform cancelled while JS is silent hands back no config`() =
    runTest(timeout = 5.seconds) {
      val bridge =
        JsPromise<Promise<RequestConfig>>().apply { resolve(JsPromise<RequestConfig>().promise) }
      val override =
        transformableConfig()
          .copy(
            transform =
              Func_std__shared_ptr_Promise_std__shared_ptr_Promise_RequestConfig_____RequestConfig_std__optional_std__unordered_map_std__string__std__string__ {
                _,
                _ ->
                bridge.promise
              }
          )
      var returned = false
      val caller =
        backgroundScope.launch {
          RequestConfigBuilder.mergeConfig(
            RequestConfigBuilder.toRequestConfig(transformableConfig()),
            override,
          )
          returned = true
        }
      runCurrent()

      caller.cancel()
      runCurrent()

      assertTrue(caller.isCompleted)
      assertFalse(returned)
    }

  @Test
  fun `a media resolve cancelled while JS is silent hands back no config`() =
    runTest(timeout = 5.seconds) {
      val silent = JsPromise<RequestConfig>()
      var returned = false
      val caller =
        backgroundScope.launch {
          RequestConfigBuilder.applyMediaResolve(
            mediaConfig(resolveSync = { silent.promise }),
            TestFixtures.track(),
          )
          returned = true
        }
      runCurrent()

      caller.cancel()
      runCurrent()

      assertTrue(caller.isCompleted)
      assertFalse(returned)
    }
}
