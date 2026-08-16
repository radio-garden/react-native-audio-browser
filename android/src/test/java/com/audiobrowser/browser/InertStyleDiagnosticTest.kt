package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.browseTrack
import com.audiobrowser.TestFixtures.section
import com.audiobrowser.TestFixtures.sectionStyle
import com.audiobrowser.TestFixtures.track
import com.audiobrowser.TestFixtures.trackStyle
import com.margelo.nitro.audiobrowser.ArtworkRendering
import com.margelo.nitro.audiobrowser.CardImage
import com.margelo.nitro.audiobrowser.GridTile
import com.margelo.nitro.audiobrowser.ImageShape
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.SectionStyle
import com.margelo.nitro.audiobrowser.StyleDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dev diagnostic for declarations that can never render (ADR 0011): the recovery for the
 * compile-time invalid-combination errors the block model deliberately gave up. Structural
 * inertness only — a surface that can't draw a property it understands is intended usage, never a
 * finding. Mirrors `ios/Tests/InertStyleDiagnosticTests.swift`.
 */
class InertStyleDiagnosticTest {

  private fun findings(
    sections: List<Section>,
    page: SectionStyle? = null,
    path: String = "/home",
  ) = InertStyleDiagnostic.findings(path, page, sections)

  // Container properties

  @Test
  fun `gridWrap on a list section is inert`() {
    val found = findings(listOf(section(style = sectionStyle(gridWrap = false), title = "Recent")))
    assertEquals(1, found.size)
    assertTrue(found[0], found[0].contains("section 'Recent'"))
    assertTrue(found[0], found[0].contains("style.gridWrap"))
    assertTrue(found[0], found[0].contains("display 'list'"))
    assertTrue(found[0], found[0].contains("/home"))
  }

  @Test
  fun `gridWrap in a grid is live`() {
    val style = sectionStyle(gridWrap = false, display = StyleDisplay.GRID)
    assertTrue(findings(listOf(section(style = style))).isEmpty())
  }

  @Test
  fun `a section inherits the page's grid display`() {
    // `display` resolves by scope override, so the page's grid makes the section's `gridWrap` live
    // without the section restating it.
    val found =
      findings(
        listOf(section(style = sectionStyle(gridWrap = false))),
        page = sectionStyle(display = StyleDisplay.GRID),
      )
    assertTrue(found.isEmpty())
  }

  @Test
  fun `gridTile on a list section is inert`() {
    val found = findings(listOf(section(style = sectionStyle(gridTile = GridTile.CARD))))
    assertEquals(1, found.size)
    assertTrue(found[0], found[0].contains("style.gridTile"))
    assertTrue(found[0], found[0].contains("section #1"))
  }

  // Item properties

  @Test
  fun `card properties need the card treatment`() {
    val plainGrid =
      sectionStyle(
        display = StyleDisplay.GRID,
        cardTint = "#1e3a8a",
        cardImage = CardImage.BACKGROUND,
      )
    val found = findings(listOf(section(style = plainGrid, title = "Featured")))
    assertEquals(2, found.size)
    assertTrue(found.any { it.contains("style.cardTint") })
    assertTrue(found.any { it.contains("style.cardImage") })
    assertTrue(found.all { it.contains("display 'grid', gridTile 'plain'") })
  }

  @Test
  fun `card properties in a card grid are live`() {
    val cards =
      sectionStyle(
        display = StyleDisplay.GRID,
        gridTile = GridTile.CARD,
        cardTint = "#1e3a8a",
        cardImage = CardImage.BACKGROUND,
      )
    assertTrue(findings(listOf(section(style = cards))).isEmpty())
  }

  @Test
  fun `imageShape needs a shaped tile`() {
    val list = sectionStyle(imageShape = ImageShape.CIRCULAR)
    val cards =
      sectionStyle(
        display = StyleDisplay.GRID,
        gridTile = GridTile.CARD,
        imageShape = ImageShape.CIRCULAR,
      )
    val condensed =
      sectionStyle(
        display = StyleDisplay.GRID,
        gridTile = GridTile.CONDENSED,
        imageShape = ImageShape.CIRCULAR,
      )
    assertEquals(1, findings(listOf(section(style = list))).size)
    assertEquals(1, findings(listOf(section(style = cards))).size)
    // Plain and condensed tiles both take a shape.
    assertTrue(findings(listOf(section(style = condensed))).isEmpty())
  }

  @Test
  fun `accessorySymbol is inert only on cards`() {
    val rows = sectionStyle(accessorySymbol = "lock.fill")
    val cards =
      sectionStyle(
        display = StyleDisplay.GRID,
        gridTile = GridTile.CARD,
        accessorySymbol = "lock.fill",
      )
    // List rows draw accessories; card elements have no slot for one.
    assertTrue(findings(listOf(section(style = rows))).isEmpty())
    val found = findings(listOf(section(style = cards)))
    assertEquals(1, found.size)
    assertTrue(found[0], found[0].contains("no accessory slot"))
  }

  @Test
  fun `artworkRendering is never inert`() {
    val stencilled =
      sectionStyle(
        display = StyleDisplay.GRID,
        gridTile = GridTile.CARD,
        artworkRendering = ArtworkRendering.STENCIL,
      )
    assertTrue(findings(listOf(section(style = stencilled))).isEmpty())
  }

  // Track-level declarations

  @Test
  fun `track declarations are reported per section`() {
    val results =
      section(
        style = sectionStyle(display = StyleDisplay.GRID),
        title = "Results",
        children =
          arrayOf(
            track(style = trackStyle(cardTint = "#111")),
            track(src = "https://s/b.mp3", style = trackStyle(cardTint = "#222")),
            track(src = "https://s/c.mp3"),
          ),
      )
    val found = findings(listOf(results))
    assertEquals(1, found.size)
    assertTrue(found[0], found[0].contains("2 tracks in section 'Results'"))
    assertTrue(found[0], found[0].contains("style.cardTint"))
  }

  @Test
  fun `a track's own shape is live in a plain grid`() {
    val grid =
      section(
        style = sectionStyle(display = StyleDisplay.GRID),
        children = arrayOf(track(style = trackStyle(imageShape = ImageShape.CIRCULAR))),
      )
    assertTrue(findings(listOf(grid)).isEmpty())
  }

  // The page block

  @Test
  fun `a page declaration live in one section is not reported`() {
    // Card tint reaches both sections; one renders cards. Live is live.
    val found =
      findings(
        listOf(
          section(style = sectionStyle(display = StyleDisplay.GRID, gridTile = GridTile.CARD)),
          section(style = sectionStyle(display = StyleDisplay.GRID)),
        ),
        page = sectionStyle(cardTint = "#1e3a8a"),
      )
    assertTrue(found.isEmpty())
  }

  @Test
  fun `a page declaration no section renders is reported once`() {
    val found =
      findings(
        listOf(
          section(style = sectionStyle(display = StyleDisplay.GRID), title = "A"),
          section(title = "B"),
        ),
        page = sectionStyle(cardTint = "#1e3a8a"),
      )
    assertEquals(1, found.size)
    assertTrue(found[0], found[0].contains("the page block"))
    assertTrue(found[0], found[0].contains("no section that inherits it"))
  }

  @Test
  fun `a section override shadows the page value`() {
    // Every section declares its own tint, so the page's is never resolved — shadowed, not inert;
    // the diagnostic reports what can't render, not what isn't reached.
    val found =
      findings(
        listOf(
          section(
            style =
              sectionStyle(display = StyleDisplay.GRID, gridTile = GridTile.CARD, cardTint = "#000")
          )
        ),
        page = sectionStyle(cardTint = "#1e3a8a"),
      )
    assertTrue(found.isEmpty())
  }

  @Test
  fun `a page-wide grid makes section declarations live`() {
    val found =
      findings(
        listOf(section(style = sectionStyle(cardTint = "#111"))),
        page = sectionStyle(display = StyleDisplay.GRID, gridTile = GridTile.CARD),
      )
    assertTrue(found.isEmpty())
  }

  // The positional `display`

  @Test
  fun `display on a playable track is inert`() {
    val tracks =
      section(
        title = "Tracks",
        children =
          arrayOf(
            track(style = trackStyle(display = StyleDisplay.GRID)),
            browseTrack(style = trackStyle(display = StyleDisplay.GRID)),
          ),
      )
    val found = findings(listOf(tracks))
    assertEquals(1, found.size)
    assertTrue(found[0], found[0].contains("1 track in section 'Tracks'"))
    assertTrue(found[0], found[0].contains("style.display"))
    assertTrue(found[0], found[0].contains("opens none"))
  }

  @Test
  fun `display on a section or page is never inert`() {
    val found =
      findings(
        listOf(
          section(
            style = sectionStyle(display = StyleDisplay.LIST),
            children = arrayOf(browseTrack()),
          )
        ),
        page = sectionStyle(display = StyleDisplay.GRID),
      )
    assertTrue(found.isEmpty())
  }

  @Test
  fun `an unstyled page is silent`() {
    assertTrue(findings(listOf(section(children = arrayOf(track(), browseTrack())))).isEmpty())
    assertTrue(findings(emptyList()).isEmpty())
  }
}
