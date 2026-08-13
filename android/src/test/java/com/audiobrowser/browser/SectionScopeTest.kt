package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.imageRowItem
import com.audiobrowser.TestFixtures.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SectionScopeTest {

  @Test
  fun `scopes to the contiguous groupTitle run`() {
    val children =
      listOf(
        track(id = "a", src = "a", groupTitle = "First"),
        track(id = "b", src = "b", groupTitle = "First"),
        track(id = "c", src = "c", groupTitle = "Second"),
        track(id = "d", src = "d", groupTitle = "Second"),
        track(id = "e", src = "e"),
      )

    val section = SectionScope.section(children, "c") as SectionScope.Section.Run
    assertEquals(listOf("c", "d"), section.tracks.map { it.src })

    val first = SectionScope.section(children, "a") as SectionScope.Section.Run
    assertEquals(listOf("a", "b"), first.tracks.map { it.src })
  }

  @Test
  fun `ungrouped items form their own run`() {
    val children =
      listOf(
        track(src = "a", groupTitle = "First"),
        track(src = "b"),
        track(src = "c"),
        track(src = "d", groupTitle = "Second"),
      )

    val section = SectionScope.section(children, "b") as SectionScope.Section.Run
    assertEquals(listOf("b", "c"), section.tracks.map { it.src })
  }

  @Test
  fun `identical titles in separate runs stay separate`() {
    val children =
      listOf(
        track(src = "a", groupTitle = "Same"),
        track(src = "x", groupTitle = "Other"),
        track(src = "b", groupTitle = "Same"),
      )

    val section = SectionScope.section(children, "a") as SectionScope.Section.Run
    assertEquals(listOf("a"), section.tracks.map { it.src })
  }

  @Test
  fun `finds the id inside an image row`() {
    val items = arrayOf(imageRowItem("s1"), imageRowItem("s2"))
    val row = track(title = "Most Played", src = null, imageRow = items)
    val children = listOf(row, track(src = "a"))

    val section = SectionScope.section(children, "s2") as SectionScope.Section.ImageRow
    assertEquals(listOf("s1", "s2"), section.items.map { it.src })
  }

  @Test
  fun `unknown id returns null`() {
    assertNull(SectionScope.section(listOf(track(src = "a")), "zz"))
  }

  @Test
  fun `locates a track by its id when it has one`() {
    // Identity rule (ADR 0008): the id wins over the src, so a __trackId stamped
    // from an id-bearing track must resolve — and its src must not.
    val children =
      listOf(
        track(id = "stable-a", src = "https://s/a.mp3", groupTitle = "Recent"),
        track(id = "stable-b", src = "https://s/b.mp3", groupTitle = "Recent"),
        track(src = "https://s/c.mp3", groupTitle = "Popular"),
      )

    val section = SectionScope.section(children, "stable-b") as SectionScope.Section.Run
    assertEquals(listOf("stable-a", "stable-b"), section.tracks.map { it.id })

    // The src is shadowed by the id — it is not the track's identity.
    assertNull(SectionScope.section(children, "https://s/b.mp3"))
  }

  @Test
  fun `finds an id-bearing item inside an image row`() {
    val items =
      arrayOf(imageRowItem("https://s/1.mp3", id = "row-1"), imageRowItem("https://s/2.mp3"))
    val row = track(title = "Most Played", src = null, imageRow = items)

    val section = SectionScope.section(listOf(row), "row-1") as SectionScope.Section.ImageRow
    assertEquals(items.toList(), section.items)
  }

  // Pins the documented precedence: sections are located by track identity (id
  // when non-blank, else src), and an identity present in both an image row and
  // the flat list resolves to the row.
  @Test
  fun `image row wins over a flat-list duplicate`() {
    val row = track(title = "Row", src = null, imageRow = arrayOf(imageRowItem("dup")))
    val children = listOf(row, track(src = "dup"), track(src = "b"))

    val section = SectionScope.section(children, "dup") as SectionScope.Section.ImageRow
    assertEquals(listOf("dup"), section.items.map { it.src })
  }

  @Test
  fun `duplicate src across runs resolves to the earlier run`() {
    val children =
      listOf(
        track(src = "dup", groupTitle = "First"),
        track(src = "x", groupTitle = "Second"),
        track(src = "dup", groupTitle = "Second"),
      )

    val section = SectionScope.section(children, "dup") as SectionScope.Section.Run
    assertEquals(listOf("dup"), section.tracks.map { it.src })
  }

  // The stamped page index pins which surface was tapped when the same
  // identity appears in more than one section; without it the precedence
  // tests above (image row first, earlier run first) apply.

  @Test
  fun `tapped index pins the flat-list copy over the image row`() {
    val row = track(title = "Row", src = null, imageRow = arrayOf(imageRowItem("dup")))
    val children = listOf(row, track(src = "dup"), track(src = "b"))

    val section = SectionScope.section(children, "dup", tappedIndex = 1) as SectionScope.Section.Run
    // The src-less row track shares the null groupTitle, so it belongs to the
    // run (expansion filters it out as non-playable) — the point is the tap
    // resolved to the flat list, not the image row.
    assertEquals(listOf(null, "dup", "b"), section.tracks.map { it.src })
    assertEquals(1, section.tappedOffset)
  }

  @Test
  fun `tapped index pins the image row when the row was tapped`() {
    val row = track(title = "Row", src = null, imageRow = arrayOf(imageRowItem("dup")))
    val children = listOf(row, track(src = "dup"), track(src = "b"))

    val section =
      SectionScope.section(children, "dup", tappedIndex = 0) as SectionScope.Section.ImageRow
    assertEquals(listOf("dup"), section.items.map { it.src })
  }

  @Test
  fun `tapped index pins the later run`() {
    val children =
      listOf(
        track(src = "dup", groupTitle = "First"),
        track(src = "x", groupTitle = "Second"),
        track(src = "dup", groupTitle = "Second"),
      )

    val section = SectionScope.section(children, "dup", tappedIndex = 2) as SectionScope.Section.Run
    assertEquals(listOf("x", "dup"), section.tracks.map { it.src })
    assertEquals(1, section.tappedOffset)
  }

  @Test
  fun `tapped index pins the exact copy within a run`() {
    val children = listOf(track(src = "a"), track(src = "b"), track(src = "a"), track(src = "c"))

    val section = SectionScope.section(children, "a", tappedIndex = 2) as SectionScope.Section.Run
    assertEquals(listOf("a", "b", "a", "c"), section.tracks.map { it.src })
    assertEquals(2, section.tappedOffset)
  }

  @Test
  fun `stale tapped index falls back to the first identity match`() {
    // The child at the stamped index no longer carries the tapped identity
    // (the list shifted) — resolution ignores the index and pins nothing.
    val children = listOf(track(src = "x"), track(src = "a"), track(src = "b"))

    val section = SectionScope.section(children, "a", tappedIndex = 0) as SectionScope.Section.Run
    assertEquals(listOf("x", "a", "b"), section.tracks.map { it.src })
    assertNull(section.tappedOffset)
  }

  @Test
  fun `out-of-range tapped index is ignored`() {
    val children = listOf(track(src = "a"), track(src = "b"))

    val section = SectionScope.section(children, "a", tappedIndex = 99) as SectionScope.Section.Run
    assertEquals(listOf("a", "b"), section.tracks.map { it.src })
    assertNull(section.tappedOffset)
  }
}
