package com.audiobrowser.http

import com.audiobrowser.TestFixtures.mediaConfig
import com.audiobrowser.TestFixtures.transformableConfig
import com.margelo.nitro.audiobrowser.HttpMethod
import com.margelo.nitro.audiobrowser.RequestConfig
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

    // Headers should be merged with override winning
    assertEquals("base-value", result.headers!!["Base-Header"])
    assertEquals("override-value", result.headers!!["Override-Header"])
    assertEquals("override-shared", result.headers!!["Shared"]) // Override wins

    // Query should be merged with override winning
    assertEquals("base-value", result.query!!["base-query"])
    assertEquals("override-value", result.query!!["override-query"])
    assertEquals("override-shared", result.query!!["shared"]) // Override wins
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
        path = "albums", // No leading slash
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
    assertEquals("https://api.example.com/albums", result2.url) // Should add slash
  }

  @Test
  fun `buildUrl should handle empty query parameters`() = runTest {
    val config =
      RequestConfig(
        method = null,
        path = "/albums",
        baseUrl = "https://api.example.com",
        headers = null,
        query = emptyMap(), // Empty map
        body = null,
        contentType = null,
        userAgent = null,
      )

    val result = RequestConfigBuilder.buildHttpRequest(config)

    assertEquals("https://api.example.com/albums", result.url) // No query string
  }

  // Run-both composition: the sync/async split replaced the old ambiguous variant.

  @Test
  fun `composeResolved merges async then sync with sync winning`() {
    val async =
      RequestConfig(null, null, "https://async.example.com", null, null, null, null, "ASYNC")
    val sync = RequestConfig(null, null, null, null, null, null, null, "SYNC")

    val merged = RequestConfigBuilder.composeResolved(async, sync)

    assertEquals("SYNC", merged?.userAgent) // sync wins
    assertEquals("https://async.example.com", merged?.baseUrl) // async's baseUrl survives
  }

  @Test
  fun `composeResolved returns the single present side, or null for neither`() {
    val cfg = RequestConfig(null, null, null, null, null, null, null, "ONLY")

    assertEquals("ONLY", RequestConfigBuilder.composeResolved(cfg, null)?.userAgent)
    assertEquals("ONLY", RequestConfigBuilder.composeResolved(null, cfg)?.userAgent)
    assertNull(RequestConfigBuilder.composeResolved(null, null))
  }

  @Test
  fun `mergeConfig MediaRequestConfig static fields merge over base and preserve callbacks`() =
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
      val merged = RequestConfigBuilder.mergeConfig(base, override)
      assertEquals("/stream.mp3", merged.path)
      assertEquals("https://media.example.com", merged.baseUrl)
      assertEquals("1", merged.headers?.get("x-base"))
      assertEquals("2", merged.headers?.get("x-media"))
      assertEquals("abc", merged.query?.get("token"))
      assertEquals("base-ua", merged.userAgent)
    }

  // Request-Config Layer rule (mirrors iOS applyLayer and the web stub): a
  // layer's static `path` never overrides the base path — the path is carried
  // from the base; only a Transform may change it.

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
  fun `mergeConfig MediaRequestConfig carries the base path over a static media path`() = runTest {
    val base = RequestConfig(null, "/stream/123", "https://api.example.com", null, null, null, null, null)
    val media = mediaConfig(path = "/static-media-path")
    val merged = RequestConfigBuilder.mergeConfig(base, media)
    assertEquals("/stream/123", merged.path)
  }
}
