#if canImport(NitroModules)
  import NitroModules
#endif

/// How CarPlay draws a section, from its resolved style and what the platform supports
/// (ADR 0011: decorations drop before layout). A wrapping grid needs a wrapping tile container
/// (iOS 26+'s element API) and becomes a list without one, so every item stays reachable
/// instead of truncating at an unknown width; a single-line grid is the legacy image row and
/// renders everywhere. These rules live here, not in the CarPlay factory, so they are testable
/// off-device.
enum SectionPresentation: Equatable {
  case list
  case singleLineRow
  case wrappingGrid

  /// - Parameter supportsWrappingGrid: whether the platform has a wrapping tile container; passed
  ///   in rather than read with `#available` so the mapping is testable off-device.
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

  /// The accessory symbol a block draws: nil when undeclared, and nil for the `"none"` sentinel,
  /// which restores the derived accessory (a browsable row's chevron).
  static func effectiveAccessorySymbol(_ style: TrackStyle?) -> String? {
    guard let symbol = style?.accessorySymbol, symbol != "none" else { return nil }
    return symbol
  }

  /// The title a tile shelf draws as the list section's header; nil when the image row's own text
  /// carries it. CarPlay draws a chevron beside an image row's text whether or not the row has a
  /// handler, so only a shelf with a "view all" `path` keeps its title there.
  ///
  /// - Parameter supportsElements: whether the platform has the iOS 26 element API; without it the
  ///   legacy image row's text always carries the title.
  static func tileSectionHeader(_ section: Section, supportsElements: Bool) -> String? {
    supportsElements && section.path == nil ? section.title : nil
  }

  enum TileElementFamily: Equatable {
    case rowElements
    case imageGridElements
    case cardElements
    case condensedElements
  }

  /// The iOS 26+ element family for a tile presentation, picked by `gridTile` (plain by default)
  /// in either wrap mode. Before iOS 26 there are no elements and the legacy image row drops the
  /// treatment.
  static func tileFamily(for style: SectionStyle?) -> TileElementFamily {
    switch style?.gridTile {
    case .image: .imageGridElements
    case .card: .cardElements
    case .condensed: .condensedElements
    case .plain, nil: .rowElements
    }
  }
}
