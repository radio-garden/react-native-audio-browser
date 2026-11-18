package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.SectionStyle
import com.margelo.nitro.audiobrowser.TrackStyle

/**
 * Resolves style declarations to effective values, per property (ADR 0011): item properties resolve
 * `track ?? section ?? page`, container properties and `display` resolve `section ?? page`. Nitro
 * flattens the spec's `extends`, so no type makes these functions read every key;
 * `src/style-resolution.test.ts` does.
 */
object StyleResolver {
  /** Folds the page block into a section's block, so item resolution needs only the section. */
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
   * No Android renderer reads it; it holds Android to the same completeness test as iOS.
   */
  fun trackStyle(track: TrackStyle?, section: SectionStyle?): TrackStyle =
    TrackStyle(
      // A track's display describes the page it opens, a section's its children's layout:
      // neither is this item's own layout.
      display = null,
      artworkRendering = track?.artworkRendering ?: section?.artworkRendering,
      imageShape = track?.imageShape ?: section?.imageShape,
      accessorySymbol = track?.accessorySymbol ?: section?.accessorySymbol,
      cardTint = track?.cardTint ?: section?.cardTint,
      cardImage = track?.cardImage ?: section?.cardImage,
    )
}
