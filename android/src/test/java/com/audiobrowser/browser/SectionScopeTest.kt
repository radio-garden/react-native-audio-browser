package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.resolvedTrack
import com.audiobrowser.TestFixtures.section
import com.audiobrowser.TestFixtures.sectionStyle
import com.audiobrowser.TestFixtures.track
import com.margelo.nitro.audiobrowser.StyleDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SectionScopeTest {

  // Two sections sharing an identity — the duplicate-heavy shape the flat model
  // had to disambiguate by precedence; sections + the stamped flat index make
  // it exact.
  private val sections =
    listOf(
      section(title = "First", children = arrayOf(track(src = "dup"), track(src = "x"))),
      section(
        title = "Second",
        children = arrayOf(track(src = "y"), track(src = "dup"), track(src = "z")),
      ),
    )

  @Test
  fun `falls back to the first section containing the identity`() {
    val scoped = SectionScope.scoped(sections, "dup")!!
    assertEquals(listOf("dup", "x"), scoped.section.children.map { it.src })
    assertNull(scoped.tappedOffset)
  }

  @Test
  fun `flat index pins the tapped section`() {
    // Flat index 3 = second section, offset 1.
    val scoped = SectionScope.scoped(sections, "dup", tappedIndex = 3)!!
    assertEquals(listOf("y", "dup", "z"), scoped.section.children.map { it.src })
    assertEquals(1, scoped.tappedOffset)
  }

  @Test
  fun `flat index pins the exact copy within a section`() {
    val playlist =
      listOf(
        section(
          children = arrayOf(track(src = "a"), track(src = "b"), track(src = "a"), track(src = "c"))
        )
      )
    val scoped = SectionScope.scoped(playlist, "a", tappedIndex = 2)!!
    assertEquals(2, scoped.tappedOffset)
  }

  @Test
  fun `stale index falls back to the first identity match`() {
    // The child at the stamped index no longer carries the tapped identity
    // (the list shifted) — resolution ignores the index and pins nothing.
    val scoped = SectionScope.scoped(sections, "dup", tappedIndex = 1)!!
    assertEquals(listOf("dup", "x"), scoped.section.children.map { it.src })
    assertNull(scoped.tappedOffset)
  }

  @Test
  fun `out-of-range index is ignored`() {
    val scoped = SectionScope.scoped(sections, "dup", tappedIndex = 99)!!
    assertEquals(listOf("dup", "x"), scoped.section.children.map { it.src })
    assertNull(scoped.tappedOffset)
  }

  @Test
  fun `vanished identity returns null`() {
    assertNull(SectionScope.scoped(sections, "gone"))
  }

  @Test
  fun `locates a track by its id when it has one`() {
    // Identity rule (ADR 0008): the id wins over the src, so a __trackId stamped
    // from an id-bearing track must resolve — and its src must not.
    val children =
      arrayOf(
        track(id = "stable-a", src = "https://s/a.mp3"),
        track(id = "stable-b", src = "https://s/b.mp3"),
      )
    val scoped = SectionScope.scoped(listOf(section(children = children)), "stable-b")!!
    assertEquals(listOf("stable-a", "stable-b"), scoped.section.children.map { it.id })

    // The src is shadowed by the id — it is not the track's identity.
    assertNull(SectionScope.scoped(listOf(section(children = children)), "https://s/b.mp3"))
  }

  // MARK: page shape helpers

  @Test
  fun `children sugar normalizes to one untitled section`() {
    val page = resolvedTrack(children = arrayOf(track(src = "a"), track(src = "b")))
    val sections = page.normalizedSections!!
    assertEquals(1, sections.size)
    assertNull(sections[0].title)
    assertEquals(listOf("a", "b"), sections[0].children.map { it.src })
  }

  @Test
  fun `sections win over children`() {
    val page =
      resolvedTrack(
        sections = arrayOf(section(title = "S", children = arrayOf(track(src = "a")))),
        children = arrayOf(track(src = "zzz")),
      )
    val sections = page.normalizedSections!!
    assertEquals(1, sections.size)
    assertEquals("S", sections[0].title)
  }

  @Test
  fun `flattened children concatenate in section order`() {
    val page =
      resolvedTrack(
        sections =
          arrayOf(
            section(title = "A", children = arrayOf(track(src = "a1"), track(src = "a2"))),
            section(children = arrayOf(track(src = "b1"))),
          )
      )
    assertEquals(listOf("a1", "a2", "b1"), page.flattenedChildren!!.map { it.src })
  }

  @Test
  fun `childless page has no sections`() {
    assertNull(resolvedTrack().normalizedSections)
  }

  // --- styleResolvedSections: the page->section fold (ADR 0011) ---

  @Test
  fun `an unstyled section reaches the surface with the page's block folded in`() {
    // The end-to-end wire for ResolvedTrack.style: without this fold, the
    // page tier is a dead field — every existing unit test would stay green
    // while a whole tier of ADR 0011 went dark in production.
    val page =
      resolvedTrack(
        style =
          sectionStyle(display = StyleDisplay.GRID, artworkRendering = null, gridWrap = false),
        sections = arrayOf(section(children = arrayOf(track(src = "a")))),
      )

    val folded = page.styleResolvedSections()!!.single()
    assertEquals(StyleDisplay.GRID, folded.style?.display)
    assertEquals(false, folded.style?.gridWrap)
    // The extras leg of this wire — folded section → per-item grid hint — is
    // asserted in MediaExtrasBuilderTest (it needs Robolectric for Bundle).
  }

  @Test
  fun `a section's own block overrides the page's in the fold`() {
    val page =
      resolvedTrack(
        style = sectionStyle(display = StyleDisplay.GRID, artworkRendering = null, gridWrap = null),
        sections =
          arrayOf(
            section(
              style =
                sectionStyle(display = StyleDisplay.LIST, artworkRendering = null, gridWrap = null),
              children = arrayOf(track(src = "a")),
            )
          ),
      )

    assertEquals(StyleDisplay.LIST, page.styleResolvedSections()!!.single().style?.display)
  }
}
