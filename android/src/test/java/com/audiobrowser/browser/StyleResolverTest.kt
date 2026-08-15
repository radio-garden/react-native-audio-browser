package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.sectionStyle
import com.audiobrowser.TestFixtures.trackStyle
import com.margelo.nitro.audiobrowser.ArtworkRendering
import com.margelo.nitro.audiobrowser.ImageShape
import com.margelo.nitro.audiobrowser.StyleDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Resolution semantics (ADR 0011): container properties and `display` resolve by scope override
 * (`section ?? page`), inherited item properties resolve `track ?? section ?? page` — and `display`
 * never inherits onto an item.
 */
class StyleResolverTest {

  @Test
  fun `section overrides the page per property`() {
    val resolved =
      StyleResolver.sectionStyle(
        section =
          sectionStyle(display = StyleDisplay.LIST, artworkRendering = null, gridWrap = null),
        page =
          sectionStyle(
            display = StyleDisplay.GRID,
            artworkRendering = ArtworkRendering.STENCIL,
            gridWrap = false,
          ),
      )
    // Declared on the section: wins. Undeclared: the page's scope-wide value.
    assertEquals(StyleDisplay.LIST, resolved.display)
    assertEquals(ArtworkRendering.STENCIL, resolved.artworkRendering)
    assertEquals(false, resolved.gridWrap)
  }

  @Test
  fun `a styleless section takes the page block`() {
    val resolved =
      StyleResolver.sectionStyle(
        section = null,
        page = sectionStyle(display = StyleDisplay.GRID, artworkRendering = null, gridWrap = true),
      )
    assertEquals(StyleDisplay.GRID, resolved.display)
    assertEquals(true, resolved.gridWrap)
  }

  @Test
  fun `track overrides the section per property`() {
    val resolved =
      StyleResolver.trackStyle(
        track = trackStyle(display = null, artworkRendering = ArtworkRendering.ORIGINAL),
        section =
          sectionStyle(
            display = StyleDisplay.GRID,
            artworkRendering = ArtworkRendering.STENCIL,
            gridWrap = null,
          ),
      )
    assertEquals(ArtworkRendering.ORIGINAL, resolved.artworkRendering)
  }

  @Test
  fun `a styleless track inherits the section value`() {
    val resolved =
      StyleResolver.trackStyle(
        track = null,
        section =
          sectionStyle(display = null, artworkRendering = ArtworkRendering.STENCIL, gridWrap = null),
      )
    assertEquals(ArtworkRendering.STENCIL, resolved.artworkRendering)
  }

  @Test
  fun `display never inherits onto an item`() {
    // The track's own display is the page promise for the page IT opens; the
    // section's describes its children's layout. Neither may surface as the
    // item's resolved display.
    val resolved =
      StyleResolver.trackStyle(
        track = trackStyle(display = StyleDisplay.GRID, artworkRendering = null),
        section =
          sectionStyle(display = StyleDisplay.GRID, artworkRendering = null, gridWrap = null),
      )
    assertNull(resolved.display)
  }

  @Test
  fun `imageShape inherits and overrides per item`() {
    // Section-wide circular (an artists shelf); one album overrides.
    val inherited =
      StyleResolver.trackStyle(
        track = null,
        section = sectionStyle(imageShape = ImageShape.CIRCULAR),
      )
    assertEquals(ImageShape.CIRCULAR, inherited.imageShape)
    val overridden =
      StyleResolver.trackStyle(
        track = trackStyle(imageShape = ImageShape.ROUNDED_RECTANGLE),
        section = sectionStyle(imageShape = ImageShape.CIRCULAR),
      )
    assertEquals(ImageShape.ROUNDED_RECTANGLE, overridden.imageShape)
  }

  @Test
  fun `accessorySymbol inherits and 'none' resolves as a value`() {
    val inherited =
      StyleResolver.trackStyle(track = null, section = sectionStyle(accessorySymbol = "lock.fill"))
    assertEquals("lock.fill", inherited.accessorySymbol)
    // 'none' is the inheritance escape — it must survive resolution intact
    // (the renderer, not the resolver, maps it to "no accessory").
    val escaped =
      StyleResolver.trackStyle(
        track = trackStyle(accessorySymbol = "none"),
        section = sectionStyle(accessorySymbol = "lock.fill"),
      )
    assertEquals("none", escaped.accessorySymbol)
  }
}
