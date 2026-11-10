package com.audiobrowser.browser

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import com.audiobrowser.TestFixtures.resolvedTrack
import com.audiobrowser.TestFixtures.section
import com.audiobrowser.TestFixtures.sectionStyle
import com.audiobrowser.TestFixtures.staticRoute
import com.audiobrowser.TestFixtures.track
import com.audiobrowser.util.BrowserPathHelper
import com.audiobrowser.util.JsPromise
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__variant_ResolvedTrack__BrowseError__std__shared_ptr_Promise_std__variant_ResolvedTrack__BrowseError_______BrowserSourceCallbackParam
import com.margelo.nitro.audiobrowser.Variant_ResolvedTrack_BrowseError_Promise_BrowseResult_
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The wiring between a tapped page and the queue that starts playing; [SectionScopeTest] covers the
 * section logic on its own. Routes serve `browseStatic`, the one browse arm that needs neither the
 * JNI bridge nor HTTP. Robolectric provides `android.net.Uri` (contextual-path parsing) and
 * `android.util.LruCache`.
 */
@RunWith(RobolectricTestRunner::class)
class BrowserManagerTest {

  private lateinit var browserManager: BrowserManager

  @Before
  fun setup() {
    browserManager = BrowserManager()
  }

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
  fun `expandQueueFromContextualPath hands a cancelled caller the cancellation, not a fallback`() =
    runTest(timeout = 5.seconds) {
      val silent = JsPromise<Variant_ResolvedTrack_BrowseError_Promise_BrowseResult_>()
      browserManager.config =
        BrowserConfig(
          routes =
            arrayOf(
              staticRoute("/home", resolvedTrack(path = "/home"))
                .copy(
                  browseStatic = null,
                  browseCallback =
                    Func_std__shared_ptr_Promise_std__variant_ResolvedTrack__BrowseError__std__shared_ptr_Promise_std__variant_ResolvedTrack__BrowseError_______BrowserSourceCallbackParam {
                      silent.promise
                    },
                )
            )
        )
      var returned = false
      val expanded =
        backgroundScope.launch {
          browserManager.expandQueueFromContextualPath(contextual("/home", "c"))
          returned = true
        }
      runCurrent()

      expanded.cancel()
      runCurrent()

      assertTrue(expanded.isCompleted)
      assertFalse(returned)
    }

  @Test
  fun `expandQueueFromContextualPath scopes the queue to the tapped section`() = runBlocking {
    serveSections(
      "/home",
      section(title = "Recent", children = arrayOf(track(src = "a"), track(src = "b"))),
      section(title = "Popular", children = arrayOf(track(src = "c"), track(src = "d"))),
    )

    val expanded = browserManager.expandQueueFromContextualPath(contextual("/home", "c"))

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
    // The stamped index 2 is the second "a".
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
      // Flat index 1 is the "dup" in Stations, not the one in Row.
      serveSections(
        "/home",
        section(
          title = "Row",
          style =
            sectionStyle(
              display = com.margelo.nitro.audiobrowser.StyleDisplay.GRID,
              artworkRendering = null,
              gridWrap = false,
            ),
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
      // Index 0 holds "x", not "a".
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
        style =
          sectionStyle(
            display = com.margelo.nitro.audiobrowser.StyleDisplay.GRID,
            artworkRendering = null,
            gridWrap = false,
          ),
        children = arrayOf(track(src = "r1"), track(src = "r2")),
      ),
      section(children = arrayOf(track(src = "a"))),
    )

    val (queue, index) =
      requireNotNull(browserManager.expandQueueFromContextualPath(contextual("/home", "r2")))

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
            // No src: browsable, so not queueable.
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

      // id when non-blank, else src (ADR 0008).
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

    // "/elsewhere" was never resolved, so only the id key can find it.
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

  // --- Track.disabled: queue expansion excludes unavailable tracks ---

  @Test
  fun `expandQueueFromContextualPath excludes disabled tracks`() = runBlocking {
    servePage(
      "/home",
      track(src = "a"),
      track(src = "unavailable", disabled = true),
      track(src = "b"),
    )

    val (queue, index) =
      requireNotNull(browserManager.expandQueueFromContextualPath(contextual("/home", "b")))

    assertEquals(listOf("a", "b"), queue.map { it.src })
    assertEquals(1, index)
  }

  @Test
  fun `expandQueueFromContextualPath aborts when the tapped track is disabled`() = runBlocking {
    servePage("/home", track(src = "a"), track(src = "unavailable", disabled = true))

    assertNull(browserManager.expandQueueFromContextualPath(contextual("/home", "unavailable")))
  }

  @Test
  fun `resolveMediaItemsForPlayback refuses a disabled track on the legacy INDEX_UNSET path`() =
    runBlocking {
      servePage("/home", track(src = "unavailable", disabled = true))
      browserManager.resolve("/home")

      val item = MediaItem.Builder().setMediaId("unavailable").build()

      try {
        browserManager.resolveMediaItemsForPlayback(listOf(item), C.INDEX_UNSET, 0)
        fail("Expected refusal of a disabled track")
      } catch (expected: IllegalStateException) {}
    }
}
