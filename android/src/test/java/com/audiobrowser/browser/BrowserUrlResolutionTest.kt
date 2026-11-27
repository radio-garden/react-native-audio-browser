package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.artworkConfig
import com.audiobrowser.TestFixtures.mediaConfig
import com.audiobrowser.TestFixtures.track
import com.audiobrowser.TestFixtures.transformableConfig
import com.audiobrowser.util.FALLBACK_TIMEOUT
import com.audiobrowser.util.JsPromise
import com.margelo.nitro.audiobrowser.ImageContext
import com.margelo.nitro.audiobrowser.ImageQueryParams
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.Track
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
      assertNull(
        bm.resolveMediaUrl(
          "https://cdn.example.com/a.mp3",
          track(src = "https://cdn.example.com/a.mp3"),
        )
      )
    }

  @Test
  fun `resolveMediaUrl applies the request layer to a relative src`() = runTest {
    bm.config = BrowserConfig(request = transformableConfig(baseUrl = "https://api.example.com"))
    val resolved = bm.resolveMediaUrl("/stream/123", track(src = "/stream/123"))
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
    val resolved = bm.resolveMediaUrl("/stream/123", track(src = "/stream/123"))
    assertEquals("https://media.example.com", resolved?.baseUrl)
    assertEquals("abc", resolved?.headers?.get("x-token"))
    assertEquals("/stream/123", resolved?.path)
  }

  @Test
  fun `resolveMediaUrl runs media resolveSync for the track it is given`() = runTest {
    val resolvedFor = mutableListOf<Track>()
    bm.config =
      BrowserConfig(
        media =
          mediaConfig(
            resolveSync = { track ->
              // Records the track and stops: a Nitro Promise cannot be returned off-device.
              resolvedFor.add(track)
              throw IllegalStateException("recorded")
            }
          )
      )
    val queued = track(id = "queued", src = "https://cdn.example.com/queued.mp3")

    bm.resolveMediaUrl("https://cdn.example.com/queued.mp3", queued)

    assertEquals(listOf(queued), resolvedFor)
  }

  @Test
  fun `resolveMediaUrl falls back to the layered config when media resolveSync never answers`() =
    runTest(timeout = 5.seconds) {
      val silent = JsPromise<RequestConfig>()
      bm.config =
        BrowserConfig(
          media =
            mediaConfig(baseUrl = "https://media.example.com", resolveSync = { silent.promise })
        )
      val resolved =
        backgroundScope.async { bm.resolveMediaUrl("/stream/1", track(src = "/stream/1")) }

      advanceTimeBy(FALLBACK_TIMEOUT - 1.seconds)
      runCurrent()
      assertFalse(resolved.isCompleted)
      advanceTimeBy(2.seconds)
      runCurrent()

      assertTrue(resolved.isCompleted)
      assertEquals("https://media.example.com", resolved.await()?.baseUrl)
      assertEquals("/stream/1", resolved.await()?.path)
    }

  @Test
  fun `resolveMediaUrl falls back to the layered config when the requestResolver throws`() =
    runTest {
      bm.config =
        BrowserConfig(
          requestResolver = { throw IllegalStateException("resolver failed") },
          media = mediaConfig(baseUrl = "https://media.example.com"),
        )
      val resolved = bm.resolveMediaUrl("/stream/1", track(src = "/stream/1"))
      assertEquals("https://media.example.com", resolved?.baseUrl)
      assertEquals("/stream/1", resolved?.path)
    }

  @Test
  fun `a throwing browseResolver also resolves the media URL without the request layer`() =
    runTest {
      bm.config =
        BrowserConfig(
          request = transformableConfig(baseUrl = "https://api.example.com"),
          browseResolver = { throw IllegalStateException("browse resolver failed") },
        )
      val resolved = bm.resolveMediaUrl("/stream/1", track(src = "/stream/1"))
      assertNull(resolved?.baseUrl)
      assertEquals("/stream/1", resolved?.path)
    }

  @Test
  fun `resolveArtworkUrl gives no artwork when artwork resolveSync never answers`() =
    runTest(timeout = 5.seconds) {
      val silent = JsPromise<RequestConfig>()
      bm.config = BrowserConfig(artwork = artworkConfig(resolveSync = { silent.promise }))
      val source =
        backgroundScope.async {
          bm.resolveArtworkUrl(track(artwork = "https://img.example.com/a.png"))
        }

      advanceTimeBy(FALLBACK_TIMEOUT - 1.seconds)
      runCurrent()
      assertFalse(source.isCompleted)
      advanceTimeBy(2.seconds)
      runCurrent()

      assertTrue(source.isCompleted)
      assertNull(source.await())
    }

  @Test
  fun `a cancelled artwork resolve stays cancelled`() =
    runTest(timeout = 5.seconds) {
      val silent = JsPromise<RequestConfig>()
      bm.config = BrowserConfig(artwork = artworkConfig(resolveSync = { silent.promise }))
      var wentOn = false
      val caller =
        backgroundScope.launch {
          bm.resolveArtworkUrl(track(artwork = "https://img.example.com/a.png"))
          wentOn = true
        }
      runCurrent()

      caller.cancel()
      runCurrent()

      assertTrue(caller.isCancelled)
      assertFalse(wentOn)
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
  fun `resolveArtworkUrl leaves the id token literal for a track without an id`() = runTest {
    bm.config =
      BrowserConfig(
        request = transformableConfig(baseUrl = "https://api.example.com"),
        artwork = artworkConfig(path = "/artwork/{id}"),
      )
    val source = bm.resolveArtworkUrl(track(artwork = null, id = null))
    assertEquals("https://api.example.com/artwork/{id}", source?.uri)
  }

  @Test
  fun `resolveArtworkUrl treats a blank id like a missing one`() = runTest {
    bm.config =
      BrowserConfig(
        request = transformableConfig(baseUrl = "https://api.example.com"),
        artwork = artworkConfig(path = "/artwork/{id}"),
      )
    val source = bm.resolveArtworkUrl(track(artwork = null, id = ""))
    assertEquals("https://api.example.com/artwork/{id}", source?.uri)
  }

  @Test
  fun `resolveArtworkUrl leaves the id token literal in header values without an id`() = runTest {
    bm.config =
      BrowserConfig(
        request = transformableConfig(baseUrl = "https://api.example.com"),
        artwork = artworkConfig(path = "/artwork", headers = mapOf("x-track" to "{id}")),
      )
    val source = bm.resolveArtworkUrl(track(artwork = null, id = null))
    assertEquals("{id}", source?.headers?.get("x-track"))

    val substituted = bm.resolveArtworkUrl(track(artwork = null, id = "abc"))
    assertEquals("abc", substituted?.headers?.get("x-track"))
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
  fun `resolveArtworkUrl adds image query params to an artwork url that has a query`() = runTest {
    bm.config =
      BrowserConfig(
        artwork = artworkConfig(imageQueryParams = ImageQueryParams(width = "w", height = "h"))
      )
    val source =
      bm.resolveArtworkUrl(
        track(artwork = "https://img.example.com/a.jpg?v=2"),
        perRouteConfig = null,
        imageContext = ImageContext(256.0, 256.0),
      )
    assertEquals("https://img.example.com/a.jpg?v=2&w=256&h=256", source?.uri)
  }

  @Test
  fun `displayArtworkSource re-resolves a registered uri with the display size`() = runTest {
    bm.config =
      BrowserConfig(
        request = transformableConfig(baseUrl = "https://api.example.com"),
        artwork = artworkConfig(imageQueryParams = ImageQueryParams(width = "w", height = "h")),
      )
    val t = track(artwork = "/art/1.png")
    val browseTime = bm.resolveArtworkUrl(t)
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
      // Setting the config clears the registry, so register after the swap, as a browse would.
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
  fun `a user-agent or content-type header in any case wins over the field`() = runTest {
    bm.config =
      BrowserConfig(
        request =
          transformableConfig(headers = mapOf("user-agent" to "mine", "CONTENT-TYPE" to "mine"))
            .copy(userAgent = "UA", contentType = "image/png")
      )
    val source = bm.unattributedArtworkSource("https://img.example.com/a.png")
    assertEquals(mapOf("user-agent" to "mine", "CONTENT-TYPE" to "mine"), source?.headers)
  }

  @Test
  fun `unattributedArtworkSource returns null when there are no static headers`() = runTest {
    bm.config = BrowserConfig()
    assertNull(bm.unattributedArtworkSource("https://img/x.png"))
  }
}
