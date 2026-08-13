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

/**
 * Favorite hydration keyed by track identity (id when non-blank, else src — see Track.identity /
 * ADR 0008). Hydration is exercised through [BrowserManager.resolve], the path every browse page
 * takes.
 *
 * Routes serve `browseStatic` (no JNI/HTTP); Robolectric is required for `android.util.LruCache`
 * and `android.net.Uri`.
 */
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

    assertEquals(mapOf("A" to false, "B" to true), favoritedByTitle(resolved.children))
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

    assertEquals(mapOf("A" to false, "B" to true), favoritedByTitle(resolved.children))
  }

  @Test
  fun `a favorite stored under src does not match an id-bearing track`() = runBlocking {
    // The identity is the id when present — a src-keyed entry must not light up
    // a track whose identity is its id (one rule, no partial matching).
    servePage("/home", track(title = "A", id = "stable-a", src = "https://s/a.mp3"))
    browserManager.setFavoriteEnabled(true)
    browserManager.setFavorites(listOf("https://s/a.mp3"))

    val resolved = browserManager.resolve("/home")

    assertEquals(mapOf("A" to false), favoritedByTitle(resolved.children))
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

    // API-provided value is never overwritten, even when the identity is favorited.
    assertEquals(mapOf("A" to false), favoritedByTitle(resolved.children))
  }

  @Test
  fun `disabled favoriting leaves tracks untouched`() = runBlocking {
    servePage("/home", track(title = "A", id = "stable-a", src = "https://s/a.mp3"))
    // favoriteEnabled defaults to false
    browserManager.setFavorites(listOf("stable-a"))

    val resolved = browserManager.resolve("/home")

    assertEquals(mapOf<String, Boolean?>("A" to null), favoritedByTitle(resolved.children))
  }

  @Test
  fun `updateFavorite adds and removes by identity`() = runBlocking {
    servePage("/home", track(title = "A", id = "stable-a", src = "https://s/a.mp3"))
    browserManager.setFavoriteEnabled(true)

    assertEquals(mapOf("A" to false), favoritedByTitle(browserManager.resolve("/home").children))

    browserManager.updateFavorite("stable-a", true)
    assertEquals(mapOf("A" to true), favoritedByTitle(browserManager.resolve("/home").children))

    browserManager.updateFavorite("stable-a", false)
    assertEquals(mapOf("A" to false), favoritedByTitle(browserManager.resolve("/home").children))
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
