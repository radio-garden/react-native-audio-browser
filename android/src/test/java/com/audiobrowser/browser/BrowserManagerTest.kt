package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.resolvedTrack
import com.audiobrowser.TestFixtures.section
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
 * [BrowserManager.expandQueueFromContextualPath] is the seam between the page a listener tapped in
 * and the queue that starts playing. Its two halves are covered on their own — the section logic in
 * [SectionScopeTest], the caller's single-track fallback in the player tests — but the wiring
 * between them is what decides whether tapping row 3 of a page resumes the right station, and only
 * these tests exercise it.
 *
 * Routes serve `browseStatic`, the one browse arm that needs neither the JNI bridge nor HTTP.
 * Robolectric is required for `android.net.Uri` (contextual-path parsing) and
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
          arrayOf(staticRoute(path, resolvedTrack(path = path, children = arrayOf(*children)))),
        singleTrack = singleTrack,
      )
  }

  /** Configures a single static route at [path] serving [sections] as its page. */
  private fun serveSections(path: String, vararg sections: com.margelo.nitro.audiobrowser.Section) {
    browserManager.config =
      BrowserConfig(
        routes =
          arrayOf(staticRoute(path, resolvedTrack(path = path, sections = arrayOf(*sections))))
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

    assertEquals(listOf("A", "B"), resolved.flattenedChildren?.map { it.title })
    // The tap target: a child's path is rewritten to point back at this page
    // plus its own id and page position (the duplicate-identity tie-breaker).
    assertEquals(
      listOf(
        BrowserPathHelper.build("/library", "a", 0),
        BrowserPathHelper.build("/library", "b", 1),
      ),
      resolved.flattenedChildren?.map { it.path },
    )
    assertEquals("/library", browserManager.getPath())
  }

  // MARK: - expandQueueFromContextualPath

  @Test
  fun `expandQueueFromContextualPath scopes the queue to the tapped section`() = runBlocking {
    serveSections(
      "/home",
      section(title = "Recent", children = arrayOf(track(src = "a"), track(src = "b"))),
      section(title = "Popular", children = arrayOf(track(src = "c"), track(src = "d"))),
    )

    val expanded = browserManager.expandQueueFromContextualPath(contextual("/home", "c"))

    // "Popular" only — a page aggregating sections must not leak next/previous across them.
    val (queue, index) = requireNotNull(expanded)
    assertEquals(listOf("c", "d"), queue.map { it.src })
    assertEquals(0, index)
  }

  @Test
  fun `expandQueueFromContextualPath indexes the tapped track within its section`() = runBlocking {
    serveSections(
      "/home",
      section(title = "Recent", children = arrayOf(track(src = "a"))),
      section(title = "Popular", children = arrayOf(track(src = "b"), track(src = "c"))),
    )

    val (queue, index) =
      requireNotNull(browserManager.expandQueueFromContextualPath(contextual("/home", "c")))

    assertEquals(listOf("b", "c"), queue.map { it.src })
    assertEquals(1, index)
  }

  @Test
  fun `expandQueueFromContextualPath pins the tapped copy of a duplicate identity`() = runBlocking {
    // The same src twice in one section (normal for music playlists): the
    // stamped page index selects the tapped copy, so "next" continues from
    // there instead of from the first copy.
    servePage("/playlist", track(src = "a"), track(src = "b"), track(src = "a"), track(src = "c"))

    val (queue, index) =
      requireNotNull(
        browserManager.expandQueueFromContextualPath(BrowserPathHelper.build("/playlist", "a", 2))
      )

    assertEquals(listOf("a", "b", "a", "c"), queue.map { it.src })
    assertEquals(2, index)
  }

  @Test
  fun `expandQueueFromContextualPath scopes a cross-section duplicate to the tapped section`() =
    runBlocking {
      // The same identity in a rail section AND the list section below it:
      // the stamped flat index resolves the tap to the list section.
      serveSections(
        "/home",
        section(
          title = "Row",
          style = com.margelo.nitro.audiobrowser.SectionStyle.RAIL,
          children = arrayOf(track(src = "dup")),
        ),
        section(title = "Stations", children = arrayOf(track(src = "dup"), track(src = "b"))),
      )

      val (queue, index) =
        requireNotNull(
          browserManager.expandQueueFromContextualPath(BrowserPathHelper.build("/home", "dup", 1))
        )

      assertEquals(listOf("dup", "b"), queue.map { it.src })
      assertEquals(0, index)
    }

  @Test
  fun `expandQueueFromContextualPath falls back to the first match on a stale index`() =
    runBlocking {
      // The list shifted since the stamp: the child at the index no longer
      // carries the identity, so resolution ignores the index and falls back
      // to the first identity match instead of aborting.
      servePage("/home", track(src = "x"), track(src = "a"), track(src = "b"))

      val (queue, index) =
        requireNotNull(
          browserManager.expandQueueFromContextualPath(BrowserPathHelper.build("/home", "a", 0))
        )

      assertEquals(listOf("x", "a", "b"), queue.map { it.src })
      assertEquals(1, index)
    }

  @Test
  fun `expandQueueFromContextualPath returns null when the id has vanished from the page`() =
    runBlocking {
      // The stored path points at a track the page no longer lists — the page changed under a
      // resumed session. Queueing the current list would resume the wrong station, so expansion
      // aborts and the caller falls back to the stored single track.
      servePage("/home", track(src = "a"), track(src = "b"))

      assertNull(browserManager.expandQueueFromContextualPath(contextual("/home", "gone")))
    }

  @Test
  fun `expandQueueFromContextualPath returns null for a non-contextual path`() = runBlocking {
    servePage("/home", track(src = "a"))

    assertNull(browserManager.expandQueueFromContextualPath("/home"))
  }

  @Test
  fun `expandQueueFromContextualPath returns null when the page has no children`() = runBlocking {
    browserManager.config =
      BrowserConfig(routes = arrayOf(staticRoute("/home", resolvedTrack(path = "/home"))))

    assertNull(browserManager.expandQueueFromContextualPath(contextual("/home", "a")))
  }

  @Test
  fun `expandQueueFromContextualPath scopes a rail section to its own tiles`() = runBlocking {
    serveSections(
      "/home",
      section(
        title = "Most Played",
        style = com.margelo.nitro.audiobrowser.SectionStyle.RAIL,
        children = arrayOf(track(src = "r1"), track(src = "r2")),
      ),
      section(children = arrayOf(track(src = "a"))),
    )

    val (queue, index) =
      requireNotNull(browserManager.expandQueueFromContextualPath(contextual("/home", "r2")))

    // A tile tap queues its section, not the list rows beside it.
    assertEquals(listOf("r1", "r2"), queue.map { it.src })
    assertEquals(1, index)
  }

  @Test
  fun `expandQueueFromContextualPath drops unplayable siblings`() = runBlocking {
    serveSections(
      "/home",
      section(
        title = "Mixed",
        children =
          arrayOf(
            track(src = "a"),
            // A browsable row sitting inside the same section — no src, so not queueable.
            track(src = null).copy(path = "/sub"),
            track(src = "b"),
          ),
      ),
    )

    val (queue, index) =
      requireNotNull(browserManager.expandQueueFromContextualPath(contextual("/home", "b")))

    assertEquals(listOf("a", "b"), queue.map { it.src })
    assertEquals(1, index)
  }

  @Test
  fun `navigate stamps the identity, not the src, into id-bearing children's contextual urls`() =
    runBlocking {
      servePage(
        "/home",
        track(id = "stable-a", src = "https://s/a.mp3", title = "A"),
        track(src = "https://s/b.mp3", title = "B"),
      )

      val resolved = browserManager.navigate("/home")

      // id when non-blank, else src — Track.identity (ADR 0008).
      assertEquals(
        listOf(
          BrowserPathHelper.build("/home", "stable-a", 0),
          BrowserPathHelper.build("/home", "https://s/b.mp3", 1),
        ),
        resolved.flattenedChildren?.map { it.path },
      )
    }

  @Test
  fun `expandQueueFromContextualPath finds the selected index by identity`() = runBlocking {
    servePage(
      "/home",
      track(id = "stable-a", src = "https://s/a.mp3"),
      track(id = "stable-b", src = "https://s/b.mp3"),
      track(id = "stable-c", src = "https://s/c.mp3"),
    )

    val (queue, index) =
      requireNotNull(browserManager.expandQueueFromContextualPath(contextual("/home", "stable-c")))

    assertEquals(listOf("stable-a", "stable-b", "stable-c"), queue.map { it.id })
    assertEquals(2, index)
  }

  @Test
  fun `getCachedTrack resolves an id-valued trackId through the cache`() = runBlocking {
    servePage("/home", track(id = "stable-b", src = "https://s/b.mp3", title = "B"))
    browserManager.navigate("/home")

    // A contextual mediaId whose __trackId is the identity (the id), with a parent path that was
    // never cached directly — the extract-then-lookup path must find it via the id key.
    val mediaId = contextual("/elsewhere", "stable-b")

    assertEquals("B", browserManager.getCachedTrack(mediaId)?.title)
  }

  @Test
  fun `expandQueueFromContextualPath honours singleTrack`() = runBlocking {
    servePage("/home", track(src = "a"), track(src = "b"), singleTrack = true)

    val (queue, index) =
      requireNotNull(browserManager.expandQueueFromContextualPath(contextual("/home", "b")))

    assertEquals(listOf("b"), queue.map { it.src })
    assertEquals(0, index)
  }
}
