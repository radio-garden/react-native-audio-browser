package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.ArtworkRendering
import com.margelo.nitro.audiobrowser.SectionStyle
import com.margelo.nitro.audiobrowser.StyleDisplay
import com.margelo.nitro.audiobrowser.TrackStyle
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
          SectionStyle(display = StyleDisplay.LIST, artworkRendering = null, gridWrap = null),
        page =
          SectionStyle(
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
        page = SectionStyle(display = StyleDisplay.GRID, artworkRendering = null, gridWrap = true),
      )
    assertEquals(StyleDisplay.GRID, resolved.display)
    assertEquals(true, resolved.gridWrap)
  }

  @Test
  fun `track overrides the section per property`() {
    val resolved =
      StyleResolver.trackStyle(
        track = TrackStyle(display = null, artworkRendering = ArtworkRendering.ORIGINAL),
        section =
          SectionStyle(
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
          SectionStyle(display = null, artworkRendering = ArtworkRendering.STENCIL, gridWrap = null),
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
        track = TrackStyle(display = StyleDisplay.GRID, artworkRendering = null),
        section =
          SectionStyle(display = StyleDisplay.GRID, artworkRendering = null, gridWrap = null),
      )
    assertNull(resolved.display)
  }
}
