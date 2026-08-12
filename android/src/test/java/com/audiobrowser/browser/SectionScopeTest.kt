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

  // Pins the documented precedence: sections are located by src, and an id
  // present in both an image row and the flat list resolves to the row.
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
}
