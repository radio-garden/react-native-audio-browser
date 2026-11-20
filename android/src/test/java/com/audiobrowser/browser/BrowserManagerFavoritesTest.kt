package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.resolvedTrack
import com.audiobrowser.TestFixtures.staticRoute
import com.audiobrowser.TestFixtures.track
import kotlinx.coroutines.runBlocking
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
  fun `setFavorites restamps the content on screen and emits it`() = runBlocking {
    servePage(
      "/home",
      track(title = "A", id = "stable-a", src = "https://s/a.mp3"),
      track(title = "B", id = "stable-b", src = "https://s/b.mp3"),
    )
    browserManager.setFavoriteEnabled(true)
    browserManager.setFavorites(listOf("stable-b"))
    browserManager.navigate("/home")
    val emitted = mutableListOf<Map<String, Boolean?>?>()
    browserManager.setOnContentChanged {
      emitted.add(favoritedByTitle(it?.flattenedChildren?.toTypedArray()))
    }

    browserManager.setFavorites(listOf("stable-a"))

    val expected = mapOf("A" to true, "B" to false)
    assertEquals(
      expected,
      favoritedByTitle(browserManager.getContent()?.flattenedChildren?.toTypedArray()),
    )
    assertEquals(listOf(expected), emitted)
  }

  @Test
  fun `setFavorites that changes no track on screen emits nothing`() = runBlocking {
    servePage("/home", track(title = "A", id = "stable-a", src = "https://s/a.mp3"))
    browserManager.setFavoriteEnabled(true)
    browserManager.setFavorites(listOf("stable-a"))
    browserManager.navigate("/home")
    var emits = 0
    browserManager.setOnContentChanged { emits++ }

    browserManager.setFavorites(listOf("stable-a", "elsewhere"))

    assertEquals(0, emits)
  }

  @Test
  fun `a removed favorite does not come back through the track cache`() = runBlocking {
    servePage(
      "/home",
      track(title = "A", id = "stable-a", src = "https://s/a.mp3", favorited = true),
    )
    browserManager.setFavoriteEnabled(true)
    browserManager.setFavorites(listOf("stable-a"))
    browserManager.resolve("/home")

    browserManager.setFavorites(emptyList())
    // A content-cache hit, as Android Auto serves the page again, re-keys the track cache.
    val served = browserManager.resolve("/home")

    assertEquals(mapOf("A" to false), favoritedByTitle(served.flattenedChildren?.toTypedArray()))
    assertEquals(false, browserManager.getCachedTrack("stable-a")?.favorited)
    assertEquals(false, browserManager.getCachedTrack("https://s/a.mp3")?.favorited)
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
