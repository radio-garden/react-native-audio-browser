package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.resolvedTrack
import com.audiobrowser.TestFixtures.section
import com.audiobrowser.TestFixtures.staticRoute
import com.audiobrowser.TestFixtures.track
import com.audiobrowser.player.PlaybackRestore
import com.audiobrowser.player.PlaybackStateStore
import com.audiobrowser.util.BrowserPathHelper
import com.margelo.nitro.audiobrowser.RepeatMode
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SectionScopeRestoreTest {

  // "dup" sits at flat indices 0 and 3, "x" at 1.
  private val sections =
    listOf(
      section(title = "First", children = arrayOf(track(src = "dup"), track(src = "x"))),
      section(
        title = "Second",
        children = arrayOf(track(src = "y"), track(src = "dup"), track(src = "z")),
      ),
    )

  private fun restoreQueue(
    sections: List<Section>,
    path: String,
    restored: Track,
    storedQueue: Set<String> = emptySet(),
  ) =
    when (
      val expansion =
        SectionScope.restoreQueue(
          sections,
          path,
          restored,
          storedQueue,
          insertGone = true,
          singleTrack = false,
        )
    ) {
      is RestoreExpansion.Queue ->
        expansion.queue.tracks.map { it.src } to expansion.queue.startIndex
      else -> expansion
    }

  private fun srcs(vararg srcs: String) = srcs.map { track(src = it) }.toTypedArray()

  // The page after "gone" left it: its later flat indexes shifted down by one.
  private val shifted =
    listOf(
      section(title = "A", children = srcs("a0", "a1")),
      section(title = "B", children = srcs("b0", "b1")),
    )

  @Test
  fun `a restore of a track still on the page queues it as a tap would`() {
    val path = BrowserPathHelper.build("/p", "x", 1)
    assertEquals(listOf("dup", "x") to 1, restoreQueue(sections, path, track(src = "x")))
  }

  @Test
  fun `a track gone from the page goes into the section sharing most of its stored queue`() {
    // Tapped at [A: a0 a1 gone] [B: b0 b1]; index 2 now falls in B.
    val path = BrowserPathHelper.build("/p", "gone", 2)
    assertEquals(
      listOf("gone", "a0", "a1") to 0,
      restoreQueue(shifted, path, track(src = "gone"), setOf("a0", "a1", "gone")),
    )
  }

  @Test
  fun `a track gone from the end of the last section goes back into it`() {
    // Tapped at [A: a0 a1] [B: b0 b1 gone]: index 4 is past the page now.
    val path = BrowserPathHelper.build("/p", "gone", 4)
    assertEquals(
      listOf("gone", "b0", "b1") to 0,
      restoreQueue(shifted, path, track(src = "gone"), setOf("b0", "b1", "gone")),
    )
    assertEquals(listOf("gone", "b0", "b1") to 0, restoreQueue(shifted, path, track(src = "gone")))
  }

  @Test
  fun `without a stored queue a gone track goes into the section its stamped index falls in`() {
    val path = BrowserPathHelper.build("/p", "gone", 3)
    assertEquals(
      listOf("gone", "y", "dup", "z") to 0,
      restoreQueue(sections, path, track(src = "gone")),
    )
  }

  @Test
  fun `a stored queue sharing nothing with the page falls back to the stamped index`() {
    val path = BrowserPathHelper.build("/p", "gone", 1)
    assertEquals(
      listOf("gone", "dup", "x") to 0,
      restoreQueue(sections, path, track(src = "gone"), setOf("elsewhere", "gone")),
    )
  }

  @Test
  fun `a gone track without a stamped index goes into the first section`() {
    val path = BrowserPathHelper.build("/p", "gone")
    assertEquals(listOf("gone", "dup", "x") to 0, restoreQueue(sections, path, track(src = "gone")))
  }

  @Test
  fun `a restore of a track the page now disables is refused`() {
    val page =
      listOf(
        section(
          title = "First",
          children = arrayOf(track(src = "a"), track(id = "off", src = "off", disabled = true)),
        )
      )
    val path = BrowserPathHelper.build("/p", "off", 1)
    assertEquals(RestoreExpansion.Refused, restoreQueue(page, path, track(id = "off", src = "off")))
  }

  private fun browserManager(vararg pages: Pair<String, Array<Section>>) =
    BrowserManager().apply {
      config =
        BrowserConfig(
          routes =
            pages
              .map { (path, sections) ->
                staticRoute(path, resolvedTrack(path = path, sections = sections))
              }
              .toTypedArray()
        )
    }

  private fun expand(manager: BrowserManager, path: String, restored: Track) = runBlocking {
    manager.expandQueueForRestore(path, restored, emptySet())
  }

  @Test
  fun `the restore expansion inserts a track gone from the page and names the page`() {
    val path = BrowserPathHelper.build("/p", "gone", 3)

    val expanded =
      expand(browserManager("/p" to sections.toTypedArray()), path, track(src = "gone"))
        as RestoreExpansion.Queue

    assertEquals(listOf("gone", "y", "dup", "z"), expanded.queue.tracks.map { it.src })
    assertEquals(0, expanded.queue.startIndex)
    assertEquals("/p", expanded.queue.sourcePath)
  }

  @Test
  fun `the restore expansion refuses a track its page now disables`() {
    val page =
      arrayOf(section(children = arrayOf(track(src = "a"), track(src = "off", disabled = true))))
    val path = BrowserPathHelper.build("/p", "off", 1)

    assertEquals(
      RestoreExpansion.Refused,
      expand(browserManager("/p" to page), path, track(src = "off")),
    )
  }

  @Test
  fun `the restore expansion refuses a track its linked page now disables`() {
    val linked = arrayOf(section(children = arrayOf(track(src = "off", disabled = true))))
    val origin = arrayOf(section(children = arrayOf(track(src = "off"))))
    val path = BrowserPathHelper.buildLinked("/linked", "off", "/home", 0)

    assertEquals(
      RestoreExpansion.Refused,
      expand(browserManager("/linked" to linked, "/home" to origin), path, track(src = "off")),
    )
  }

  @Test
  fun `the restore expansion of an enabled track without a src on its page is unavailable`() {
    val page =
      arrayOf(section(children = arrayOf(track(src = "a"), track(id = "nosrc", src = null))))
    val path = BrowserPathHelper.build("/p", "nosrc", 1)

    assertEquals(
      RestoreExpansion.Unavailable,
      expand(browserManager("/p" to page), path, track(id = "nosrc", src = null)),
    )
  }

  @Test
  fun `the restore expansion refuses a track whose stamped index pins a disabled copy on its linked page`() {
    val linked =
      arrayOf(section(children = arrayOf(track(src = "off", disabled = true), track(src = "a"))))
    val origin = arrayOf(section(children = arrayOf(track(src = "off"))))
    // A linked path carrying an index into the linked page, which pins the disabled copy.
    val path = BrowserPathHelper.build("/linked", "off", 0) + "&__from=%2Fhome&__fromIndex=0"

    assertEquals(
      RestoreExpansion.Refused,
      expand(browserManager("/linked" to linked, "/home" to origin), path, track(src = "off")),
    )
  }

  @Test
  fun `a stored queue whose index no longer holds the track gives no overlap`() {
    // The queue was saved after the track moved on: its index holds "b1", not "gone".
    val state =
      PlaybackStateStore.PersistedState(
        track = track(src = "gone").copy(path = BrowserPathHelper.build("/p", "gone", 0)),
        positionMs = 0,
        repeatMode = RepeatMode.OFF,
        shuffleEnabled = false,
        playbackSpeed = 1f,
        queue = listOf(track(src = "b0"), track(src = "b1")),
        queueIndex = 1,
      )
    val manager = browserManager("/p" to shifted.toTypedArray())

    val expanded =
      runBlocking { PlaybackRestore.expand(state) { manager } } as RestoreExpansion.Queue

    assertEquals(listOf("gone", "a0", "a1"), expanded.queue.tracks.map { it.src })
  }

  @Test
  fun `the restore expansion of a page that fails is unavailable`() {
    val path = BrowserPathHelper.build("/missing", "a", 0)

    assertEquals(
      RestoreExpansion.Unavailable,
      expand(browserManager("/p" to sections.toTypedArray()), path, track(src = "a")),
    )
  }
}
