package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.imageRowItem
import com.audiobrowser.TestFixtures.resolvedTrack
import com.audiobrowser.TestFixtures.staticRoute
import com.audiobrowser.TestFixtures.track
import com.audiobrowser.util.BrowserPathHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Navigation and queue-expansion glue.
 *
 * [BrowserManager.expandQueueFromContextualUrl] is the seam between the page a listener tapped in
 * and the queue that starts playing. Its two halves are covered on their own — the section logic in
 * [SectionScopeTest], the caller's single-track fallback in the player tests — but the wiring
 * between them is what decides whether tapping row 3 of a page resumes the right station, and only
 * these tests exercise it.
 *
 * Routes serve `browseStatic`, the one browse arm that needs neither the JNI bridge nor HTTP.
 * Robolectric is required for `android.net.Uri` (contextual-URL parsing) and
 * `android.util.LruCache` (the content cache).
 */
@RunWith(RobolectricTestRunner::class)
class BrowserManagerTest {

  private lateinit var browserManager: BrowserManager

  @Before
  fun setup() {
    browserManager = BrowserManager()
  }

  /** Configures a single static route at [path] serving [children] as its page. */
  private fun servePage(
    path: String,
    vararg children: com.margelo.nitro.audiobrowser.Track,
    singleTrack: Boolean = false,
  ) {
    browserManager.config =
      BrowserConfig(
        routes =
          arrayOf(staticRoute(path, resolvedTrack(url = path, children = arrayOf(*children)))),
        singleTrack = singleTrack,
      )
  }

  private fun contextual(path: String, trackId: String) = BrowserPathHelper.build(path, trackId)

  @Test
  fun `getPath returns default path initially`() {
    assertEquals("/", browserManager.getPath())
  }

  @Test
  fun `navigate with no routes throws ContentNotFoundException`() {
    runBlocking {
      browserManager.config = BrowserConfig()
      try {
        browserManager.navigate("/test")
        fail("Expected ContentNotFoundException")
      } catch (e: ContentNotFoundException) {
        assertEquals("/test", e.path)
      }
    }
  }

  @Test
  fun `navigate updates current path`() {
    runBlocking {
      browserManager.config = BrowserConfig()
      try {
        browserManager.navigate("/artists/123")
      } catch (_: ContentNotFoundException) {}

      assertEquals("/artists/123", browserManager.getPath())
    }
  }

  @Test
  fun `navigate with empty path throws ContentNotFoundException`() {
    runBlocking {
      browserManager.config = BrowserConfig()
      try {
        browserManager.navigate("")
        fail("Expected ContentNotFoundException")
      } catch (_: ContentNotFoundException) {}
    }
  }

  @Test
  fun `navigate serves a static route and gives playable children contextual urls`() = runBlocking {
    servePage("/library", track(src = "a", title = "A"), track(src = "b", title = "B"))

    val resolved = browserManager.navigate("/library")

    assertEquals(listOf("A", "B"), resolved.children?.map { it.title })
    // The tap target: a child's url is rewritten to point back at this page plus its own id.
    assertEquals(
      listOf(contextual("/library", "a"), contextual("/library", "b")),
      resolved.children?.map { it.url },
    )
    assertEquals("/library", browserManager.getPath())
  }

  // MARK: - expandQueueFromContextualUrl

  @Test
  fun `expandQueueFromContextualUrl scopes the queue to the tapped section`() = runBlocking {
    servePage(
      "/home",
      track(src = "a", groupTitle = "Recent"),
      track(src = "b", groupTitle = "Recent"),
      track(src = "c", groupTitle = "Popular"),
      track(src = "d", groupTitle = "Popular"),
    )

    val expanded = browserManager.expandQueueFromContextualUrl(contextual("/home", "c"))

    // "Popular" only — a page aggregating sections must not leak next/previous across them.
    val (queue, index) = requireNotNull(expanded)
    assertEquals(listOf("c", "d"), queue.map { it.src })
    assertEquals(0, index)
  }

  @Test
  fun `expandQueueFromContextualUrl indexes the tapped track within its section`() = runBlocking {
    servePage(
      "/home",
      track(src = "a", groupTitle = "Recent"),
      track(src = "b", groupTitle = "Popular"),
      track(src = "c", groupTitle = "Popular"),
    )

    val (queue, index) =
      requireNotNull(browserManager.expandQueueFromContextualUrl(contextual("/home", "c")))

    assertEquals(listOf("b", "c"), queue.map { it.src })
    assertEquals(1, index)
  }

  @Test
  fun `expandQueueFromContextualUrl returns null when the id has vanished from the page`() =
    runBlocking {
      // The stored url points at a track the page no longer lists — the page changed under a
      // resumed session. Queueing the current list would resume the wrong station, so expansion
      // aborts and the caller falls back to the stored single track.
      servePage("/home", track(src = "a"), track(src = "b"))

      assertNull(browserManager.expandQueueFromContextualUrl(contextual("/home", "gone")))
    }

  @Test
  fun `expandQueueFromContextualUrl returns null for a non-contextual url`() = runBlocking {
    servePage("/home", track(src = "a"))

    assertNull(browserManager.expandQueueFromContextualUrl("/home"))
  }

  @Test
  fun `expandQueueFromContextualUrl returns null when the page has no children`() = runBlocking {
    browserManager.config =
      BrowserConfig(routes = arrayOf(staticRoute("/home", resolvedTrack(url = "/home"))))

    assertNull(browserManager.expandQueueFromContextualUrl(contextual("/home", "a")))
  }

  @Test
  fun `expandQueueFromContextualUrl expands an image row into its items`() = runBlocking {
    servePage(
      "/home",
      track(
        title = "Most Played",
        src = null,
        imageRow = arrayOf(imageRowItem("r1"), imageRowItem("r2")),
      ),
      track(src = "a"),
    )

    val (queue, index) =
      requireNotNull(browserManager.expandQueueFromContextualUrl(contextual("/home", "r2")))

    // A tile tap queues its row, not the list rows beside it.
    assertEquals(listOf("r1", "r2"), queue.map { it.src })
    assertEquals(1, index)
  }

  @Test
  fun `expandQueueFromContextualUrl drops unplayable siblings`() = runBlocking {
    servePage(
      "/home",
      track(src = "a", groupTitle = "Mixed"),
      // A browsable row sitting inside the same section — no src, so not queueable.
      track(src = null, groupTitle = "Mixed").copy(url = "/sub"),
      track(src = "b", groupTitle = "Mixed"),
    )

    val (queue, index) =
      requireNotNull(browserManager.expandQueueFromContextualUrl(contextual("/home", "b")))

    assertEquals(listOf("a", "b"), queue.map { it.src })
    assertEquals(1, index)
  }

  @Test
  fun `expandQueueFromContextualUrl honours singleTrack`() = runBlocking {
    servePage(
      "/home",
      track(src = "a", groupTitle = "Recent"),
      track(src = "b", groupTitle = "Recent"),
      singleTrack = true,
    )

    val (queue, index) =
      requireNotNull(browserManager.expandQueueFromContextualUrl(contextual("/home", "b")))

    assertEquals(listOf("b"), queue.map { it.src })
    assertEquals(0, index)
  }
}
