#if canImport(NitroModules)
  import NitroModules
#endif

/// The one place style declarations resolve to effective values (ADR 0011).
///
/// Two mechanisms, deliberately distinct:
/// - **Inherited item properties** resolve `track ?? section ?? page`,
///   per property — a track's own declaration wins over the blocks that
///   contain it.
/// - **Container properties and the positional `display`** resolve by scope
///   override: `section ?? page` — the page declares for its whole scope, a
///   section overrides for its own children. `display` is never inherited
///   item-to-container: each holder describes its own children, and a
///   track's `display` is the promise for the page *it* opens, not its
///   rendering inside this section.
///
/// Every key of `SectionStyle` must be read by `sectionStyle(section:page:)`
/// and every inherited key of `TrackStyle` by `trackStyle(track:section:)` —
/// Nitro flattens the spec's `extends`, so `src/style-resolution.test.ts`
/// enforces this completeness, not the type system.
enum StyleResolver {
  /// Folds the page block into a section's block: every `SectionStyle` key
  /// resolves `section ?? page`. The result is the section's effective
  /// block; item resolution then only needs `track ?? section`.
  static func sectionStyle(section: SectionStyle?, page: SectionStyle?) -> SectionStyle {
    // Argument order is the generated init's (own properties before inherited
    // ones — Nitro flattens `extends` own-props-first); the stub mirrors it.
    SectionStyle(
      gridWrap: section?.gridWrap ?? page?.gridWrap,
      display: section?.display ?? page?.display,
      artworkRendering: section?.artworkRendering ?? page?.artworkRendering,
    )
  }

  /// Resolves a track's effective item properties against its (page-folded)
  /// section block.
  static func trackStyle(track: TrackStyle?, section: SectionStyle?) -> TrackStyle {
    TrackStyle(
      // Positional deny-list: `display` is never inherited onto an item —
      // resolved nil, so no renderer can mistake the handle's page promise
      // for this item's own layout.
      display: nil,
      artworkRendering: track?.artworkRendering ?? section?.artworkRendering,
    )
  }
}
