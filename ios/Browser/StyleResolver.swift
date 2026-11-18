#if canImport(NitroModules)
  import NitroModules
#endif

/// Resolves style declarations to effective values (ADR 0011). Nothing in the types makes these
/// merges read a new style key: `src/style-resolution.test.ts` reads this source to check.
enum StyleResolver {
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

  /// A track's effective item properties: each key is the track's own value, else `section`'s.
  /// Pass the block `sectionStyle` returned, so the page's values reach the track.
  static func trackStyle(track: TrackStyle?, section: SectionStyle?) -> TrackStyle {
    TrackStyle(
      // A track's `display` lays out the page it opens, not its own row: never inherited.
      display: nil,
      artworkRendering: track?.artworkRendering ?? section?.artworkRendering,
      imageShape: track?.imageShape ?? section?.imageShape,
      accessorySymbol: track?.accessorySymbol ?? section?.accessorySymbol,
      cardTint: track?.cardTint ?? section?.cardTint,
      cardImage: track?.cardImage ?? section?.cardImage,
    )
  }
}
