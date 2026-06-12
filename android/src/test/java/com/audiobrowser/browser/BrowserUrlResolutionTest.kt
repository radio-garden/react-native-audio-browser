package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests browser/BrowserUrlResolution.kt — media/artwork URL resolution owned by
 * BrowserManager (the Kotlin analog of iOS BrowserManager+URLResolution.swift).
 * Static configs only (Nitro Promises are JNI-backed; see file-top note in
 * BrowserManagerLayerResolutionTest).
 */
class BrowserUrlResolutionTest {

  private lateinit var bm: BrowserManager

  @Before
  fun setup() {
    bm = BrowserManager()
  }

  private fun requestLayer(baseUrl: String) =
    TransformableRequestConfig(
      transform = null,
      transformSync = null,
      method = null,
      path = null,
      baseUrl = baseUrl,
      headers = null,
      query = null,
      body = null,
      contentType = null,
      userAgent = null,
    )

  @Test
  fun `resolveMediaUrl returns null when neither media nor request layer is configured`() =
    runTest {
      bm.config = BrowserConfig()
      assertNull(bm.resolveMediaUrl("https://cdn.example.com/a.mp3"))
    }

  @Test
  fun `resolveMediaUrl applies the request layer to a relative src`() = runTest {
    bm.config = BrowserConfig(request = requestLayer("https://api.example.com"))
    val resolved = bm.resolveMediaUrl("/stream/123")
    assertEquals("https://api.example.com", resolved?.baseUrl)
    assertEquals("/stream/123", resolved?.path)
  }

  @Test
  fun `resolveMediaUrl merges static media config over the request layer`() = runTest {
    bm.config =
      BrowserConfig(
        request = requestLayer("https://api.example.com"),
        media =
          MediaRequestConfig(
            resolve = null,
            resolveSync = null,
            transform = null,
            transformSync = null,
            method = null,
            path = null,
            baseUrl = "https://media.example.com",
            headers = mapOf("x-token" to "abc"),
            query = null,
            body = null,
            contentType = null,
            userAgent = null,
          ),
      )
    val resolved = bm.resolveMediaUrl("/stream/123")
    assertEquals("https://media.example.com", resolved?.baseUrl)
    assertEquals("abc", resolved?.headers?.get("x-token"))
    assertEquals("/stream/123", resolved?.path)
  }
}
