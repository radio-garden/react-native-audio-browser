package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.SectionStyle
import com.margelo.nitro.audiobrowser.TrackStyle

/**
 * Resolves style declarations to effective values, per property. Item properties resolve `track ??
 * section ?? page`. Container properties and the positional `display` resolve `section ?? page` and
 * never inherit onto an item. See docs/adr/0011-style-is-a-declaration-block.md.
 *
 * Nitro flattens the spec's `extends`, so the type system does not make these functions read every
 * key; `src/style-resolution.test.ts` does. A key added to `SectionStyle` must be read by
 * [sectionStyle], and an inherited key added to `TrackStyle` by [trackStyle].
 */
object StyleResolver {
  /** Folds the page block into a section's block, so item resolution only needs the section. */
  fun sectionStyle(section: SectionStyle?, page: SectionStyle?): SectionStyle =
    SectionStyle(
      display = section?.display ?: page?.display,
      artworkRendering = section?.artworkRendering ?: page?.artworkRendering,
      imageShape = section?.imageShape ?: page?.imageShape,
      accessorySymbol = section?.accessorySymbol ?: page?.accessorySymbol,
      cardTint = section?.cardTint ?: page?.cardTint,
      cardImage = section?.cardImage ?: page?.cardImage,
      gridWrap = section?.gridWrap ?: page?.gridWrap,
      gridTile = section?.gridTile ?: page?.gridTile,
    )

  /**
   * Resolves a track's item properties against its section block, already folded by [sectionStyle].
   * The result's `display` is always null.
   *
   * No Android renderer reads the result yet; it exists so the completeness test holds both
   * platforms to the same rule.
   */
  fun trackStyle(track: TrackStyle?, section: SectionStyle?): TrackStyle =
    TrackStyle(
      // A track's display describes the page it opens and a section's describes its children's
      // layout; neither is this item's own layout, so display never inherits onto an item.
      display = null,
      artworkRendering = track?.artworkRendering ?: section?.artworkRendering,
      imageShape = track?.imageShape ?: section?.imageShape,
      // "none" resolves like any other value; the renderer, not the resolver, reads it as no
      // accessory.
      accessorySymbol = track?.accessorySymbol ?: section?.accessorySymbol,
      cardTint = track?.cardTint ?: section?.cardTint,
      cardImage = track?.cardImage ?: section?.cardImage,
    )
}
