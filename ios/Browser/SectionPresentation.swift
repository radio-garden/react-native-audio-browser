#if canImport(NitroModules)
  import NitroModules
#endif

/// The rendered form of a section — the single place where (resolved style,
/// platform capability) maps to what CarPlay draws. Degradation drops
/// decorations before layout (ADR 0011): a *wrapping* grid can only reach the
/// tile path where the platform has a wrapping tile container (CarPlay:
/// iOS 26+'s element API); without one it becomes a list, where every item
/// stays reachable instead of truncating at an unknowable width. A
/// single-line grid (`gridWrap: false`) renders everywhere — the legacy image
/// row IS the single-line presentation.
enum SectionPresentation: Equatable {
  case list
  case singleLineRow
  case wrappingGrid

  /// - Parameter supportsWrappingGrid: whether the platform has a wrapping
  ///   tile container. Injected (rather than read via `#available` here) so
  ///   the mapping is testable off-device.
  init(for style: SectionStyle?, supportsWrappingGrid: Bool) {
    guard style?.display == .grid else {
      self = .list
      return
    }
    if style?.gridWrap == false {
      self = .singleLineRow
    } else if supportsWrappingGrid {
      self = .wrappingGrid
    } else {
      self = .list
    }
  }

  /// The accessory symbol a resolved block actually draws: nil when
  /// undeclared, and nil for the `'none'` sentinel — the inheritance escape
  /// that restores the derived accessory (a browsable row's chevron). Here,
  /// not in the CarPlay factory, so the sentinel rule is testable off-device.
  static func effectiveAccessorySymbol(_ style: TrackStyle?) -> String? {
    guard let symbol = style?.accessorySymbol, symbol != "none" else { return nil }
    return symbol
  }

  /// The element family a tile presentation renders on iOS 26+ — here, not
  /// in the CarPlay factory, so the style→family mapping is testable
  /// off-device. `gridTile` picks the family (plain by default); the plain
  /// single-line shelf keeps row elements, the one plain element with a
  /// subtitle slot. Pre-26 has no element API at all: the treatment drops
  /// with the legacy image row (decorations drop before layout).
  enum TileElementFamily: Equatable {
    case rowElements
    case imageGridElements
    case cardElements
    case condensedElements
  }

  static func tileFamily(for style: SectionStyle?, singleLine: Bool) -> TileElementFamily {
    switch style?.gridTile {
    case .card: .cardElements
    case .condensed: .condensedElements
    case .plain, nil: singleLine ? .rowElements : .imageGridElements
    }
  }
}
