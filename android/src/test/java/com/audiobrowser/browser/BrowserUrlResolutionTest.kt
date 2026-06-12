package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.artworkConfig
import com.audiobrowser.TestFixtures.mediaConfig
import com.audiobrowser.TestFixtures.track
import com.audiobrowser.TestFixtures.transformableConfig
import com.margelo.nitro.audiobrowser.ImageContext
import com.margelo.nitro.audiobrowser.ImageQueryParams
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests browser/BrowserUrlResolution.kt — media/artwork URL resolution owned by BrowserManager (the
 * Kotlin analog of iOS BrowserManager+URLResolution.swift). Static configs only (Nitro Promises are
 * JNI-backed; see file-top note in BrowserManagerLayerResolutionTest).
 */
class BrowserUrlResolutionTest {

  private lateinit var bm: BrowserManager

  @Before
  fun setup() {
    bm = BrowserManager()
  }

  @Test
  fun `resolveMediaUrl returns null when neither media nor request layer is configured`() =
    runTest {
      bm.config = BrowserConfig()
      assertNull(bm.resolveMediaUrl("https://cdn.example.com/a.mp3"))
    }

  @Test
  fun `resolveMediaUrl applies the request layer to a relative src`() = runTest {
    bm.config = BrowserConfig(request = transformableConfig(baseUrl = "https://api.example.com"))
    val resolved = bm.resolveMediaUrl("/stream/123")
    assertEquals("https://api.example.com", resolved?.baseUrl)
    assertEquals("/stream/123", resolved?.path)
  }

  @Test
  fun `resolveMediaUrl merges static media config over the request layer`() = runTest {
    bm.config =
      BrowserConfig(
        request = transformableConfig(baseUrl = "https://api.example.com"),
        media =
          mediaConfig(baseUrl = "https://media.example.com", headers = mapOf("x-token" to "abc")),
      )
    val resolved = bm.resolveMediaUrl("/stream/123")
    assertEquals("https://media.example.com", resolved?.baseUrl)
    assertEquals("abc", resolved?.headers?.get("x-token"))
    assertEquals("/stream/123", resolved?.path)
  }

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
        request = transformableConfig(baseUrl = "https://api.example.com"),
        artwork = artworkConfig(query = mapOf("sig" to "xyz")),
      )
    val source = bm.resolveArtworkUrl(track(artwork = "/art/1.png"))
    assertEquals("https://api.example.com/art/1.png?sig=xyz", source?.uri)
  }

  @Test
  fun `resolveArtworkUrl substitutes the id token`() = runTest {
    bm.config =
      BrowserConfig(
        request = transformableConfig(baseUrl = "https://api.example.com"),
        artwork = artworkConfig(path = "/artwork/{id}"),
      )
    val source = bm.resolveArtworkUrl(track(artwork = null, id = "abc"))
    assertEquals("https://api.example.com/artwork/abc", source?.uri)
  }

  @Test
  fun `resolveArtworkUrl applies image query params from the image context`() = runTest {
    bm.config =
      BrowserConfig(
        request = transformableConfig(baseUrl = "https://api.example.com"),
        artwork = artworkConfig(imageQueryParams = ImageQueryParams(width = "w", height = "h")),
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
  fun `displayArtworkSource re-resolves a registered uri with the display size`() = runTest {
    bm.config =
      BrowserConfig(
        request = transformableConfig(baseUrl = "https://api.example.com"),
        artwork = artworkConfig(imageQueryParams = ImageQueryParams(width = "w", height = "h")),
      )
    val t = track(artwork = "/art/1.png")
    val browseTime = bm.resolveArtworkUrl(t) // no size at browse time
    bm.artworkResolutions.register(browseTime!!.uri, t, null)

    val displayTime = bm.displayArtworkSource(browseTime.uri, ImageContext(512.0, 512.0))
    assertTrue(displayTime!!.uri.contains("w=512"))
    assertTrue(displayTime.uri.contains("h=512"))
  }

  @Test
  fun `displayArtworkSource returns null for an unknown uri`() = runTest {
    bm.config = BrowserConfig()
    assertNull(bm.displayArtworkSource("https://img/unknown.png", ImageContext(512.0, 512.0)))
  }

  @Test
  fun `displayArtworkSource entries without a per-route config use the current global config`() =
    runTest {
      // Register under config A (entry stores perRouteConfig = null for the global
      // fallback) and replace the global config: display-time must resolve through
      // the NEW config, not a pinned old one. Note the config setter clears the
      // registry, so re-register after the swap as a browse under config B would.
      bm.config =
        BrowserConfig(
          request = transformableConfig(baseUrl = "https://api.example.com"),
          artwork = artworkConfig(query = mapOf("sig" to "old")),
        )
      val t = track(artwork = "/art/1.png")
      val browseTime = bm.resolveArtworkUrl(t)!!

      bm.config =
        BrowserConfig(
          request = transformableConfig(baseUrl = "https://api.example.com"),
          artwork = artworkConfig(query = mapOf("sig" to "new")),
        )
      bm.artworkResolutions.register(browseTime.uri, t, null)

      val displayTime = bm.displayArtworkSource(browseTime.uri, null)
      assertTrue(displayTime!!.uri, displayTime.uri.contains("sig=new"))
    }

  @Test
  fun `unattributedArtworkSource keeps the uri and adds static headers`() = runTest {
    bm.config =
      BrowserConfig(
        request = transformableConfig(headers = mapOf("x-auth" to "key")),
        artwork = artworkConfig(headers = mapOf("x-art" to "1")),
      )
    val source = bm.unattributedArtworkSource("https://img.example.com/a.png?sig=1")
    assertEquals("https://img.example.com/a.png?sig=1", source?.uri)
    assertEquals("key", source?.headers?.get("x-auth"))
    assertEquals("1", source?.headers?.get("x-art"))
  }

  @Test
  fun `unattributedArtworkSource returns null when there are no static headers`() = runTest {
    bm.config = BrowserConfig()
    assertNull(bm.unattributedArtworkSource("https://img/x.png"))
  }
}
