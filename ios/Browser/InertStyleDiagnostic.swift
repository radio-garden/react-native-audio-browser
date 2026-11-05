import os.log

#if canImport(NitroModules)
  import NitroModules
#endif

/// Debug-build warnings for style declarations a resolved page can never render (ADR 0011).
/// Only structural inertness is reported: a declaration its own resolved block makes
/// unreadable on every surface. A declaration one surface can't draw (`imageShape` on
/// Android Auto, a card on older CarPlay) is intended usage and never reported.
enum InertStyleDiagnostic {
  /// Logs `findings` in debug builds; a release build does nothing, not even walk the page.
  static func warn(path: String, pageStyle: SectionStyle?, sections: [Section]) {
    #if DEBUG
      let logger = Logger(subsystem: "com.audiobrowser", category: "InertStyleDiagnostic")
      for finding in findings(path: path, pageStyle: pageStyle, sections: sections) {
        logger.warning("\(finding, privacy: .public)")
      }
    #endif
  }

  // Each style key of the spec is a case here or listed in `alwaysRenderable` / `positional`:
  // src/style-resolution.test.ts parses these declarations, one `case` per line, and fails
  // on a key missing from all three.
  private enum Property: String, CaseIterable {
    case gridWrap
    case gridTile
    case imageShape
    case accessorySymbol
    case cardTint
    case cardImage

    /// Item properties may also be declared per track; container properties never reach one.
    var isItemProperty: Bool {
      switch self {
      case .gridWrap, .gridTile: false
      case .imageShape, .accessorySymbol, .cardTint, .cardImage: true
      }
    }

    func isInert(in context: Context) -> Bool {
      switch self {
      case .gridWrap, .gridTile: !context.isGrid
      case .imageShape: !context.isShaped
      case .accessorySymbol: context.isGrid && !context.isShaped
      case .cardTint, .cardImage: !context.isCard
      }
    }

    var rule: String {
      switch self {
      case .gridWrap, .gridTile: "`\(rawValue)` renders only in a grid"
      case .imageShape: "only image and condensed tiles have a shape"
      case .accessorySymbol: "among grid tiles only image and condensed ones have an accessory slot"
      case .cardTint, .cardImage: "the card properties render only under `gridTile: 'card'`"
      }
    }

    var hint: String {
      switch self {
      case .gridWrap, .gridTile:
        "Declare `display: 'grid'` on the section or the page, or drop `\(rawValue)`."
      case .imageShape:
        "Use `gridTile: 'image'` or `'condensed'` in a grid, or drop `imageShape`."
      case .accessorySymbol:
        "Use `gridTile: 'image'` or `'condensed'`, or a list, or drop `accessorySymbol`."
      case .cardTint, .cardImage:
        "Declare `display: 'grid'` with `gridTile: 'card'`, or drop `\(rawValue)`."
      }
    }
  }

  // `allCases` is computed; read it once rather than per section.
  private static let allProperties = Property.allCases

  // Keys with no structural inertness rule, read only by src/style-resolution.test.ts.
  // `display` is inert only on a playable track, which `playableDisplayFinding` checks.
  static let alwaysRenderable = ["artworkRendering"]
  static let positional = ["display"]

  private struct Context {
    let isGrid: Bool
    let tile: GridTile

    /// Folds through `StyleResolver` so the prediction matches what the renderers draw.
    init(section: SectionStyle?, page: SectionStyle?) {
      let folded = StyleResolver.sectionStyle(section: section, page: page)
      isGrid = folded.display == .grid
      tile = folded.gridTile ?? .plain
    }

    var isCard: Bool {
      guard isGrid else { return false }
      return switch tile {
      case .card: true
      case .plain, .image, .condensed: false
      }
    }

    var isShaped: Bool {
      guard isGrid else { return false }
      return switch tile {
      case .image, .condensed: true
      case .plain, .card: false
      }
    }

    var described: String {
      isGrid ? "display 'grid', gridTile '\(tile.stringValue)'" : "display 'list'"
    }
  }

  /// Every inert declaration on a resolved page, as ready-to-log messages.
  /// `sections` carry their own blocks unfolded: each finding names the level that declared it.
  static func findings(path: String, pageStyle: SectionStyle?, sections: [Section]) -> [String] {
    var findings: [String] = []
    // A page-level declaration is reported only if no section it reaches renders it.
    var pageReached: Set<Property> = []
    var pageRendered: Set<Property> = []

    for (index, section) in sections.enumerated() {
      let context = Context(section: section.style, page: pageStyle)
      let sectionLabel = label(section, index: index)

      for property in allProperties {
        let inert = property.isInert(in: context)

        if declared(property, in: section.style) {
          if inert {
            findings.append(
              message(
                property, path: path, owner: sectionLabel,
                scope: "its effective style is \(context.described)",
              ))
          }
        } else if declared(property, in: pageStyle) {
          pageReached.insert(property)
          if !inert { pageRendered.insert(property) }
        }

        guard property.isItemProperty, inert else { continue }
        let tracks = section.children.count(where: { declared(property, in: $0.style) })
        if tracks > 0 {
          findings.append(
            message(
              property, path: path, owner: "\(trackCount(tracks)) in \(sectionLabel)",
              scope: "the section's effective style is \(context.described)",
            ))
        }
      }

      if let finding = playableDisplayFinding(section, path: path, label: sectionLabel) {
        findings.append(finding)
      }
    }

    for property in allProperties
      where pageReached.contains(property) && !pageRendered.contains(property)
    {
      findings.append(
        message(
          property, path: path, owner: "the page block",
          scope: "no section that inherits it renders one",
        ))
    }

    return findings
  }

  private static func playableDisplayFinding(
    _ section: Section, path: String, label: String,
  ) -> String? {
    let tracks = section.children.count(where: { $0.src != nil && $0.style?.display != nil })
    guard tracks > 0 else { return nil }
    return message(
      path: path, owner: "\(trackCount(tracks)) in \(label)", key: "display",
      reason: "`display` is the layout promise for the page a track opens, and a track "
        + "rendered playable opens none",
      hint: "Drop `display`, or give the track a `path` if it should be browsable.",
    )
  }

  private static func message(
    _ property: Property, path: String, owner: String, scope: String,
  ) -> String {
    message(
      path: path, owner: owner, key: property.rawValue,
      reason: "\(property.rule), but \(scope)", hint: property.hint,
    )
  }

  private static func message(
    path: String, owner: String, key: String, reason: String, hint: String,
  ) -> String {
    "Inert style declaration on page '\(path)': `style.\(key)` on \(owner) "
      + "never renders — \(reason). \(hint)"
  }

  private static func label(_ section: Section, index: Int) -> String {
    if let title = section.title, !title.isEmpty { return "section '\(title)'" }
    return "section #\(index + 1)"
  }

  private static func trackCount(_ count: Int) -> String {
    "\(count) track\(count == 1 ? "" : "s")"
  }

  // Nitro flattens the spec's `extends`, so `SectionStyle` and `TrackStyle` share no type.
  private static func declared(_ property: Property, in style: SectionStyle?) -> Bool {
    guard let style else { return false }
    switch property {
    case .gridWrap: return style.gridWrap != nil
    case .gridTile: return style.gridTile != nil
    case .imageShape: return style.imageShape != nil
    case .accessorySymbol: return style.accessorySymbol != nil
    case .cardTint: return style.cardTint != nil
    case .cardImage: return style.cardImage != nil
    }
  }

  private static func declared(_ property: Property, in style: TrackStyle?) -> Bool {
    guard let style else { return false }
    switch property {
    case .gridWrap, .gridTile: return false
    case .imageShape: return style.imageShape != nil
    case .accessorySymbol: return style.accessorySymbol != nil
    case .cardTint: return style.cardTint != nil
    case .cardImage: return style.cardImage != nil
    }
  }
}
