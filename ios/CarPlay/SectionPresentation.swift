#if canImport(NitroModules)
  import NitroModules
#endif

/// How CarPlay draws a section (ADR 0011: decorations drop before layout). A wrapping grid
/// without a wrapping tile container (iOS 26+) becomes a list, so every item stays reachable
/// instead of truncating at an unknown width.
enum SectionPresentation: Equatable {
  case list
  case singleLineRow
  case wrappingGrid

  /// `supportsWrappingGrid` is passed in, not read with `#available`, so this is testable
  /// off-device.
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

  /// Nil when undeclared, and for the `"none"` sentinel, which restores the derived accessory (a
  /// browsable row's chevron).
  static func effectiveAccessorySymbol(_ style: TrackStyle?) -> String? {
    guard let symbol = style?.accessorySymbol, symbol != "none" else { return nil }
    return symbol
  }

  /// The title a tile shelf draws as the list section's header; nil when the image row's own text
  /// carries it. CarPlay draws a chevron beside an image row's text whether or not the row has a
  /// handler, so only a shelf with a "view all" `path` keeps its title there. Without the
  /// iOS 26 elements the row's text always carries it.
  static func tileSectionHeader(_ section: Section, supportsElements: Bool) -> String? {
    supportsElements && section.path == nil ? section.title : nil
  }

  enum TileElementFamily: Equatable {
    case rowElements
    case imageGridElements
    case cardElements
    case condensedElements
  }

  static func tileFamily(for style: SectionStyle?) -> TileElementFamily {
    switch style?.gridTile {
    case .image: .imageGridElements
    case .card: .cardElements
    case .condensed: .condensedElements
    case .plain, nil: .rowElements
    }
  }
}
