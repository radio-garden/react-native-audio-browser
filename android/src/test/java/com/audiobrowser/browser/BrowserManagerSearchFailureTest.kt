package com.audiobrowser.browser

import androidx.media3.common.MediaItem
import com.audiobrowser.TestFixtures.transformableConfig
import com.audiobrowser.navigationErrorFor
import com.margelo.nitro.audiobrowser.NativeRouteEntry
import com.margelo.nitro.audiobrowser.NavigationError
import com.margelo.nitro.audiobrowser.NavigationErrorType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A search through the search route's HTTP config that the server fails. */
@RunWith(RobolectricTestRunner::class)
class BrowserManagerSearchFailureTest {

  private lateinit var server: MockWebServer
  private lateinit var browserManager: BrowserManager

  @Before
  fun setup() {
    server = MockWebServer()
    server.start()
    browserManager = BrowserManager()
    browserManager.config =
      BrowserConfig(
        request = transformableConfig(baseUrl = server.url("/").toString().trimEnd('/')),
        routes =
          arrayOf(
            NativeRouteEntry(
              path = BrowserManager.SEARCH_ROUTE_PATH,
              browseCallback = null,
              browseConfig = null,
              browseStatic = null,
              searchCallback = null,
              searchConfig = transformableConfig(path = "/search"),
              media = null,
              artwork = null,
            )
          ),
      )
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun navigationErrorOfSearch(response: MockResponse): NavigationError {
    server.enqueue(response)
    return navigationErrorFor(
      assertThrows(Exception::class.java) { runBlocking { browserManager.search("jazz") } }
    )
  }

  private fun tappedSearchResult(mediaId: String) =
    MediaItem.Builder()
      .setMediaId(mediaId)
      .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("jazz").build())
      .build()

  @Test
  fun `a non-2xx search is an http-error with its status`() {
    val error = navigationErrorOfSearch(MockResponse().setResponseCode(503))

    assertEquals(NavigationErrorType.HTTP_ERROR, error.code)
    assertEquals(503.0, error.statusCode!!, 0.0)
    assertEquals(false, error.statusCodeSuccess)
  }

  @Test
  fun `a search whose request fails is a network-error`() {
    val error =
      navigationErrorOfSearch(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

    assertEquals(NavigationErrorType.NETWORK_ERROR, error.code)
  }

  @Test
  fun `a 2xx search body that is not a track array is an http-error with a successful status`() {
    val error = navigationErrorOfSearch(MockResponse().setBody("<html>Sign in</html>"))

    assertEquals(NavigationErrorType.HTTP_ERROR, error.code)
    assertEquals(200.0, error.statusCode!!, 0.0)
    assertEquals(true, error.statusCodeSuccess)
  }

  @Test
  fun `a failed search does not become the cached search`() {
    server.enqueue(MockResponse().setResponseCode(500))

    runCatching { runBlocking { browserManager.search("jazz") } }

    assertNull(browserManager.getCachedSearchResults("jazz"))
  }

  @Test
  fun `a 2xx empty array is a search with no results`() {
    server.enqueue(MockResponse().setBody("[]"))

    val results = runBlocking { browserManager.searchPlayable("jazz") }

    assertNull(results)
  }

  @Test
  fun `a tapped search result plays the cached results without searching again`() {
    server.enqueue(
      MockResponse()
        .setBody(
          """[{"title":"A","src":"https://s/a.mp3"},{"title":"B","src":"https://s/b.mp3"}]"""
        )
    )
    runBlocking { browserManager.search("jazz") }

    val playback = runBlocking {
      browserManager.resolveMediaItemsForPlayback(
        listOf(tappedSearchResult("https://s/b.mp3")),
        0,
        0,
      )
    }

    assertEquals(
      listOf("https://s/a.mp3", "https://s/b.mp3"),
      playback.mediaItems.map { it.mediaId },
    )
    assertEquals(1, playback.startIndex)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun `a tapped search result whose search fails plays on its own`() {
    server.enqueue(MockResponse().setResponseCode(503))

    val playback = runBlocking {
      browserManager.resolveMediaItemsForPlayback(
        listOf(tappedSearchResult("https://s/b.mp3")),
        0,
        0,
      )
    }

    assertEquals(listOf("https://s/b.mp3"), playback.mediaItems.map { it.mediaId })
  }
}
