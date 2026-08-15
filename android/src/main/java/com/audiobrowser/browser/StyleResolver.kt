package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.SectionStyle
import com.margelo.nitro.audiobrowser.TrackStyle

/**
 * The one place style declarations resolve to effective values (ADR 0011).
 *
 * Two mechanisms, deliberately distinct:
 * - **Inherited item properties** resolve `track ?? section ?? page`, per property — a track's own
 *   declaration wins over the blocks that contain it.
 * - **Container properties and the positional `display`** resolve by scope override: `section ??
 *   page` — the page declares for its whole scope, a section overrides for its own children.
 *   `display` is never inherited item-to-container: each holder describes its own children, and a
 *   track's `display` is the promise for the page *it* opens, not its rendering inside this
 *   section.
 *
 * Every key of `SectionStyle` must be read by [sectionStyle] and every inherited key of
 * `TrackStyle` by [trackStyle] — Nitro flattens the spec's `extends`, so
 * `src/style-resolution.test.ts` enforces this completeness, not the type system.
 */
object StyleResolver {
  /**
   * Folds the page block into a section's block: every `SectionStyle` key resolves `section ??
   * page`. The result is the section's effective block; item resolution then only needs `track ??
   * section`.
   */
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
   * Resolves a track's effective item properties against its (page-folded) section block.
   *
   * No Android renderer consumes the result yet — today's only inherited property
   * (`artworkRendering`) is CarPlay-rendered — but the resolver ships on both platforms so the
   * completeness test can hold every future inherited key to the same rule from day one.
   */
  fun trackStyle(track: TrackStyle?, section: SectionStyle?): TrackStyle =
    TrackStyle(
      // Positional deny-list: `display` is never inherited onto an item — resolved null, so no
      // renderer can mistake the handle's page promise for this item's own layout.
      display = null,
      artworkRendering = track?.artworkRendering ?: section?.artworkRendering,
      imageShape = track?.imageShape ?: section?.imageShape,
      // 'none' resolves like any value — a renderer treats it as "no accessory, derived
      // behavior" (the inheritance escape). No Android surface draws these properties today.
      accessorySymbol = track?.accessorySymbol ?: section?.accessorySymbol,
      cardTint = track?.cardTint ?: section?.cardTint,
      cardImage = track?.cardImage ?: section?.cardImage,
    )
}
