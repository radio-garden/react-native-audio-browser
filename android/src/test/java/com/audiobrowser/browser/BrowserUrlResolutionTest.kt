package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.ArtworkRequestConfig
import com.margelo.nitro.audiobrowser.ImageContext
import com.margelo.nitro.audiobrowser.ImageQueryParams
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

  private fun track(artwork: String?, id: String? = null, src: String? = "https://s/a.mp3") =
    Track(
      id = id,
      url = null,
      src = src,
      artwork = artwork,
      artworkSource = null,
      request = null,
      artworkCarPlayTinted = null,
      title = "T",
      subtitle = null,
      artist = null,
      albumUrl = null,
      album = null,
      description = null,
      genre = null,
      duration = null,
      style = null,
      childrenStyle = null,
      favorited = null,
      groupTitle = null,
      live = null,
      imageRow = null,
    )

  private fun staticArtworkConfig(
    path: String? = null,
    query: Map<String, String>? = null,
    imageQueryParams: ImageQueryParams? = null,
  ) =
    ArtworkRequestConfig(
      resolve = null,
      resolveSync = null,
      transform = null,
      transformSync = null,
      imageQueryParams = imageQueryParams,
      method = null,
      path = path,
      baseUrl = null,
      headers = null,
      query = query,
      body = null,
      contentType = null,
      userAgent = null,
    )

  @Test
  fun `resolveArtworkUrl returns original artwork untouched when no artwork config`() = runTest {
    bm.config = BrowserConfig()
    val source = bm.resolveArtworkUrl(track(artwork = "https://img.example.com/a.png"))
    assertEquals("https://img.example.com/a.png", source?.uri)
  }

  @Test
  fun `resolveArtworkUrl returns null when no config and no artwork`() = runTest {
    bm.config = BrowserConfig()
    assertNull(bm.resolveArtworkUrl(track(artwork = null)))
  }

  @Test
  fun `resolveArtworkUrl layers request and artwork config over the track artwork`() = runTest {
    bm.config =
      BrowserConfig(
        request = requestLayer("https://api.example.com"),
        artwork = staticArtworkConfig(query = mapOf("sig" to "xyz")),
      )
    val source = bm.resolveArtworkUrl(track(artwork = "/art/1.png"))
    assertEquals("https://api.example.com/art/1.png?sig=xyz", source?.uri)
  }

  @Test
  fun `resolveArtworkUrl substitutes the id token`() = runTest {
    bm.config =
      BrowserConfig(
        request = requestLayer("https://api.example.com"),
        artwork = staticArtworkConfig(path = "/artwork/{id}"),
      )
    val source = bm.resolveArtworkUrl(track(artwork = null, id = "abc"))
    assertEquals("https://api.example.com/artwork/abc", source?.uri)
  }

  @Test
  fun `resolveArtworkUrl applies image query params from the image context`() = runTest {
    bm.config =
      BrowserConfig(
        request = requestLayer("https://api.example.com"),
        artwork =
          staticArtworkConfig(imageQueryParams = ImageQueryParams(width = "w", height = "h")),
      )
    val source =
      bm.resolveArtworkUrl(
        track(artwork = "/art/1.png"),
        perRouteConfig = null,
        imageContext = ImageContext(256.0, 256.0),
      )
    assertTrue(source!!.uri.contains("w=256"))
    assertTrue(source.uri.contains("h=256"))
  }

  @Test
  fun `displayArtworkSource re-resolves a registered uri with the size hint`() = runTest {
    bm.config =
      BrowserConfig(
        request = requestLayer("https://api.example.com"),
        artwork =
          staticArtworkConfig(imageQueryParams = ImageQueryParams(width = "w", height = "h")),
      )
    val t = track(artwork = "/art/1.png")
    val browseTime = bm.resolveArtworkUrl(t) // no size at browse time
    bm.artworkResolutions.register(browseTime!!.uri, t, null)

    val displayTime = bm.displayArtworkSource(browseTime.uri, sizeHintPixels = 512)
    assertTrue(displayTime!!.uri.contains("w=512"))
    assertTrue(displayTime.uri.contains("h=512"))
  }

  @Test
  fun `displayArtworkSource returns null for an unknown uri`() = runTest {
    bm.config = BrowserConfig()
    assertNull(bm.displayArtworkSource("https://img/unknown.png", 512))
  }
}
