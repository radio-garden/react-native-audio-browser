#if canImport(NitroModules)
  import NitroModules
#endif

/// Resolves style declarations to effective values (ADR 0011). Nitro flattens the spec's
/// `extends`, so nothing in the types makes these merges read a new style key:
/// `src/style-resolution.test.ts` does.
enum StyleResolver {
  /// A section's effective block: each key is the section's own value, else the page's,
  /// `display` included.
  static func sectionStyle(section: SectionStyle?, page: SectionStyle?) -> SectionStyle {
    SectionStyle(
      gridWrap: section?.gridWrap ?? page?.gridWrap,
      gridTile: section?.gridTile ?? page?.gridTile,
      display: section?.display ?? page?.display,
      artworkRendering: section?.artworkRendering ?? page?.artworkRendering,
      imageShape: section?.imageShape ?? page?.imageShape,
      accessorySymbol: section?.accessorySymbol ?? page?.accessorySymbol,
      cardTint: section?.cardTint ?? page?.cardTint,
      cardImage: section?.cardImage ?? page?.cardImage,
    )
  }

  /// A track's effective item properties: each key is the track's own value, else its
  /// section's. Pass the section block `sectionStyle` returned, so the page's values reach
  /// the track. `display` is always nil.
  static func trackStyle(track: TrackStyle?, section: SectionStyle?) -> TrackStyle {
    TrackStyle(
      // A track's `display` describes the page it opens, not its own layout here, so it is
      // never inherited onto an item.
      display: nil,
      artworkRendering: track?.artworkRendering ?? section?.artworkRendering,
      imageShape: track?.imageShape ?? section?.imageShape,
      // `'none'` stays a value here; the renderer reads it as no accessory. As nil it would
      // inherit the section's symbol.
      accessorySymbol: track?.accessorySymbol ?? section?.accessorySymbol,
      cardTint: track?.cardTint ?? section?.cardTint,
      cardImage: track?.cardImage ?? section?.cardImage,
    )
  }
}
