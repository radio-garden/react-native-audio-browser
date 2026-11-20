package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.resolvedTrack
import com.audiobrowser.TestFixtures.staticRoute
import com.audiobrowser.TestFixtures.track
import com.audiobrowser.TestFixtures.transformableConfig
import com.margelo.nitro.audiobrowser.NativeRouteEntry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric provides the android.util.LruCache and android.net.Uri that BrowserManager uses.
@RunWith(RobolectricTestRunner::class)
class BrowserManagerFavoritesTest {

  private lateinit var browserManager: BrowserManager

  @Before
  fun setup() {
    browserManager = BrowserManager()
  }

  private fun servePage(path: String, vararg children: com.margelo.nitro.audiobrowser.Track) {
    browserManager.config =
      BrowserConfig(
        routes =
          arrayOf(staticRoute(path, resolvedTrack(path = path, children = arrayOf(*children))))
      )
  }

  private fun favoritedByTitle(children: Array<com.margelo.nitro.audiobrowser.Track>?) =
    children?.associate { it.title to it.favorited }

  @Test
  fun `hydrates by id when the track has one`() = runBlocking {
    servePage(
      "/home",
      track(title = "A", id = "stable-a", src = "https://s/a.mp3"),
      track(title = "B", id = "stable-b", src = "https://s/b.mp3"),
    )
    browserManager.setFavoriteEnabled(true)
    browserManager.setFavorites(listOf("stable-b"))

    val resolved = browserManager.resolve("/home")

    assertEquals(
      mapOf("A" to false, "B" to true),
      favoritedByTitle(resolved.flattenedChildren?.toTypedArray()),
    )
  }

  @Test
  fun `falls back to src for id-less tracks`() = runBlocking {
    servePage(
      "/home",
      track(title = "A", src = "https://s/a.mp3"),
      track(title = "B", src = "https://s/b.mp3"),
    )
    browserManager.setFavoriteEnabled(true)
    browserManager.setFavorites(listOf("https://s/b.mp3"))

    val resolved = browserManager.resolve("/home")

    assertEquals(
      mapOf("A" to false, "B" to true),
      favoritedByTitle(resolved.flattenedChildren?.toTypedArray()),
    )
  }

  @Test
  fun `a favorite stored under src does not match an id-bearing track`() = runBlocking {
    servePage("/home", track(title = "A", id = "stable-a", src = "https://s/a.mp3"))
    browserManager.setFavoriteEnabled(true)
    browserManager.setFavorites(listOf("https://s/a.mp3"))

    val resolved = browserManager.resolve("/home")

    assertEquals(mapOf("A" to false), favoritedByTitle(resolved.flattenedChildren?.toTypedArray()))
  }

  @Test
  fun `caller-set favorited wins over hydration`() = runBlocking {
    servePage(
      "/home",
      track(title = "A", id = "stable-a", src = "https://s/a.mp3", favorited = false),
    )
    browserManager.setFavoriteEnabled(true)
    browserManager.setFavorites(listOf("stable-a"))

    val resolved = browserManager.resolve("/home")

    assertEquals(mapOf("A" to false), favoritedByTitle(resolved.flattenedChildren?.toTypedArray()))
  }

  @Test
  fun `favorited in an HTTP route's response wins over hydration`() = runBlocking {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setBody(
          """{"path":"/home","title":"Home","children":[
            {"title":"A","src":"https://s/a.mp3","favorited":true},
            {"title":"B","src":"https://s/b.mp3","favorited":false}
          ]}"""
        )
    )
    server.start()
    try {
      browserManager.config =
        BrowserConfig(
          request = transformableConfig(baseUrl = server.url("/").toString().trimEnd('/')),
          routes =
            arrayOf(
              NativeRouteEntry(
                path = "/home",
                browseCallback = null,
                browseConfig = transformableConfig(),
                browseStatic = null,
                searchCallback = null,
                searchConfig = null,
                media = null,
                artwork = null,
              )
            ),
        )
      browserManager.setFavoriteEnabled(true)
      browserManager.setFavorites(listOf("https://s/b.mp3"))

      val resolved = browserManager.resolve("/home")

      assertEquals(
        mapOf("A" to true, "B" to false),
        favoritedByTitle(resolved.flattenedChildren?.toTypedArray()),
      )
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `disabled favoriting leaves tracks untouched`() = runBlocking {
    servePage("/home", track(title = "A", id = "stable-a", src = "https://s/a.mp3"))
    // favoriteEnabled defaults to false.
    browserManager.setFavorites(listOf("stable-a"))

    val resolved = browserManager.resolve("/home")

    assertEquals(
      mapOf<String, Boolean?>("A" to null),
      favoritedByTitle(resolved.flattenedChildren?.toTypedArray()),
    )
  }

  @Test
  fun `updateFavorite adds and removes by identity`() = runBlocking {
    servePage("/home", track(title = "A", id = "stable-a", src = "https://s/a.mp3"))
    browserManager.setFavoriteEnabled(true)

    assertEquals(
      mapOf("A" to false),
      favoritedByTitle(browserManager.resolve("/home").flattenedChildren?.toTypedArray()),
    )

    browserManager.updateFavorite("stable-a", true)
    assertEquals(
      mapOf("A" to true),
      favoritedByTitle(browserManager.resolve("/home").flattenedChildren?.toTypedArray()),
    )

    browserManager.updateFavorite("stable-a", false)
    assertEquals(
      mapOf("A" to false),
      favoritedByTitle(browserManager.resolve("/home").flattenedChildren?.toTypedArray()),
    )
  }

  @Test
  fun `getCachedTrack re-hydrates favorites by identity`() = runBlocking {
    servePage("/home", track(title = "A", id = "stable-a", src = "https://s/a.mp3"))
    browserManager.setFavoriteEnabled(true)
    browserManager.resolve("/home")

    browserManager.updateFavorite("stable-a", true)

    assertEquals(true, browserManager.getCachedTrack("stable-a")?.favorited)
  }
}
