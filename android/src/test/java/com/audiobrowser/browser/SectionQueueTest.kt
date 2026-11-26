package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.resolvedTrack
import com.audiobrowser.TestFixtures.section
import com.audiobrowser.TestFixtures.staticRoute
import com.audiobrowser.TestFixtures.track
import com.audiobrowser.util.BrowserPathHelper
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.SectionQueue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A preview section whose `queue` is `'path'` queues the page its `path` links to. */
@RunWith(RobolectricTestRunner::class)
class SectionQueueTest {

  private lateinit var browserManager: BrowserManager

  private val fullList = arrayOf(track(src = "a"), track(src = "b"), track(src = "c"))

  @Before
  fun setup() {
    browserManager = BrowserManager()
  }

  private fun serve(vararg pages: Pair<String, Array<Section>>) {
    browserManager.config =
      BrowserConfig(
        routes =
          pages
            .map { (path, sections) ->
              staticRoute(path, resolvedTrack(path = path, sections = sections))
            }
            .toTypedArray()
      )
  }

  private fun preview(queue: SectionQueue?, path: String? = "/recent") =
    section(title = "Recent", path = path, children = arrayOf(track(src = "a"), track(src = "b")))
      .copy(queue = queue)

  @Test
  fun `a tap in a path-queued preview queues the whole linked page from the tapped track`() =
    runBlocking {
      serve(
        "/home" to arrayOf(preview(SectionQueue.PATH)),
        "/recent" to arrayOf(section(children = fullList)),
      )

      val tapped = browserManager.navigate("/home").flattenedChildren!![1]
      val (queue, index) =
        requireNotNull(browserManager.expandQueueFromContextualPath(tapped.path!!))

      assertEquals(listOf("a", "b", "c"), queue.map { it.src })
      assertEquals(1, index)
    }

  @Test
  fun `a path-queued preview's tap shares its source with the linked page's own tap`() =
    runBlocking {
      serve(
        "/home" to arrayOf(preview(SectionQueue.PATH)),
        "/recent" to arrayOf(section(children = fullList)),
      )

      val fromPreview = browserManager.navigate("/home").flattenedChildren!![1].path!!
      val fromPage = browserManager.navigate("/recent").flattenedChildren!![1].path!!

      assertEquals(
        BrowserPathHelper.stripTrackId(fromPage),
        BrowserPathHelper.stripTrackId(fromPreview),
      )
    }

  @Test
  fun `expanding a linked page leaves the next tap on the browsed page where it was`() =
    runBlocking {
      // Ids, as on a car: the car sends the id, and contextualPathFor finds the tapped list by it.
      val x = track(id = "x", src = "x")
      val y = track(id = "y", src = "y")
      serve(
        "/home" to
          arrayOf(
            section(path = "/popular", children = arrayOf(x)).copy(queue = SectionQueue.PATH),
            section(path = "/channels", children = arrayOf(y)).copy(queue = SectionQueue.PATH),
          ),
        "/popular" to arrayOf(section(children = arrayOf(x, y))),
      )
      browserManager.navigate("/home")

      browserManager.expandQueueFromContextualPath(browserManager.contextualPathFor("x")!!)

      assertEquals(
        BrowserPathHelper.buildLinked("/channels", "y", "/home", 1),
        browserManager.contextualPathFor("y"),
      )
    }

  @Test
  fun `a path-queued preview whose linked page fails queues its own children, from its page`() =
    runBlocking {
      serve("/home" to arrayOf(preview(SectionQueue.PATH)))

      val tapped = browserManager.navigate("/home").flattenedChildren!![1]
      val expanded = requireNotNull(browserManager.expandQueueFromContextualPath(tapped.path!!))

      assertEquals(listOf("a", "b"), expanded.tracks.map { it.src })
      assertEquals(1, expanded.startIndex)
      // Not the linked page's source: a later tap in the preview must not skip in this queue.
      assertEquals("/home", expanded.sourcePath)
    }

  @Test
  fun `a path-queued preview whose linked page lost the track queues its own children`() =
    runBlocking {
      serve(
        "/home" to arrayOf(preview(SectionQueue.PATH)),
        "/recent" to arrayOf(section(children = arrayOf(track(src = "a"), track(src = "c")))),
      )

      val tapped = browserManager.navigate("/home").flattenedChildren!![1]
      val (queue, index) =
        requireNotNull(browserManager.expandQueueFromContextualPath(tapped.path!!))

      assertEquals(listOf("a", "b"), queue.map { it.src })
      assertEquals(1, index)
    }

  @Test
  fun `a fallback leaves a later tap in the preview free to queue the linked page`() = runBlocking {
    serve("/home" to arrayOf(preview(SectionQueue.PATH)))
    val tapped = browserManager.navigate("/home").flattenedChildren!![1].path!!
    val fallback = requireNotNull(browserManager.expandQueueFromContextualPath(tapped))

    serve(
      "/home" to arrayOf(preview(SectionQueue.PATH)),
      "/recent" to arrayOf(section(children = fullList)),
    )
    val again = requireNotNull(browserManager.expandQueueFromContextualPath(tapped))

    // A tap skips in place only when its parent is the queue's source.
    assertNotEquals(BrowserPathHelper.stripTrackId(tapped), fallback.sourcePath)
    assertEquals(listOf("a", "b", "c"), again.tracks.map { it.src })
    assertEquals("/recent", again.sourcePath)
  }

  @Test
  fun `a preview without a queue still queues only its own children`() = runBlocking {
    serve(
      "/home" to arrayOf(preview(queue = null)),
      "/recent" to arrayOf(section(children = fullList)),
    )

    val tapped = browserManager.navigate("/home").flattenedChildren!![1]
    val (queue, _) = requireNotNull(browserManager.expandQueueFromContextualPath(tapped.path!!))

    assertEquals(listOf("a", "b"), queue.map { it.src })
  }

  @Test
  fun `a path-queued section without a path, or an empty one, queues its own children`() =
    runBlocking {
      for (path in listOf(null, "")) {
        serve("/home" to arrayOf(preview(SectionQueue.PATH, path = path)))

        val tapped = browserManager.navigate("/home").flattenedChildren!![1]
        val (queue, _) = requireNotNull(browserManager.expandQueueFromContextualPath(tapped.path!!))

        assertEquals(listOf("a", "b"), queue.map { it.src })
      }
    }

  @Test
  fun `decodes queue from the wire, and an unknown value as none`() {
    val json = Json { ignoreUnknownKeys = true }
    fun queueOf(value: String) =
      json
        .decodeFromString<JsonResolvedTrack>(
          """{"path":"/home","sections":[{"queue":"$value","children":[{"title":"S","src":"s"}]}]}"""
        )
        .toNitro(requestedPath = "/home")
        .sections
        ?.first()
        ?.queue

    assertEquals(SectionQueue.PATH, queueOf("path"))
    assertEquals(SectionQueue.SECTION, queueOf("section"))
    assertNull(queueOf("everything"))
  }
}
