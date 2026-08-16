import os.log

#if canImport(NitroModules)
  import NitroModules
#endif

/// Dev diagnostic (ADR 0011): the declarations on a resolved page that can
/// never render.
///
/// The block model deliberately gave up compile-time invalid-combination
/// errors — combinations are not invalid, they are *inert*, the way
/// `grid-template` on a non-grid element is inert in CSS. This is the
/// recovery: at page resolution every declaration its own block makes
/// unreadable is reported once, with the effective context and the fix.
///
/// The line it draws, and never crosses: only **structural** inertness — a
/// declaration the resolved block itself renders unreadable, on every surface
/// (`gridWrap` outside a grid, `cardTint` with no card treatment in scope).
/// *Surface* inertness is never reported, because declarations are
/// aspirational: `imageShape` on Android Auto, or a card treatment on a
/// pre-26 CarPlay, is intended usage — the renderer drops what it can't draw.
///
/// Runs where every page passes regardless of its source (static config,
/// route callback, or fetched browse JSON), and only on a cache miss, so a
/// mistake is reported once per resolution rather than once per serve.
///
/// Debug builds only: `warn` is the whole seam, so a release build never even
/// walks the page (the trade is that a page served only to a Release build —
/// TestFlight, production browse JSON — reports nothing; reproduce it against
/// a debug build).
enum InertStyleDiagnostic {
  /// Reports every inert declaration on a resolved page. The one gate — the
  /// `#if` is here rather than at the call sites so callers read
  /// unconditionally and the rule set stays free of build-configuration
  /// knowledge.
  static func warn(path: String, pageStyle: SectionStyle?, sections: [Section]) {
    #if DEBUG
      let logger = Logger(subsystem: "com.audiobrowser", category: "InertStyleDiagnostic")
      for finding in findings(path: path, pageStyle: pageStyle, sections: sections) {
        logger.warning("\(finding, privacy: .public)")
      }
    #endif
  }

  /// The declarations that carry a structural inertness condition.
  ///
  /// Two keys of the block are deliberately absent — `display` and
  /// `artworkRendering`, dispositioned below instead.
  private enum Property: String, CaseIterable {
    case gridWrap
    case gridTile
    case imageShape
    case accessorySymbol
    case cardTint
    case cardImage

    /// Item properties inherit (`track ?? section ?? page`) and so may also
    /// be declared per track; container properties resolve by scope override
    /// (`section ?? page`) and never reach an item.
    var isItemProperty: Bool {
      switch self {
      case .gridWrap, .gridTile: false
      case .imageShape, .accessorySymbol, .cardTint, .cardImage: true
      }
    }

    /// Whether a value of this property, resolved into `context`, has any
    /// rendering to affect. Each case is the spec's own "inert unless" clause.
    func isInert(in context: Context) -> Bool {
      switch self {
      case .gridWrap, .gridTile: !context.isGrid
      case .imageShape: !context.isGrid || context.isCard
      case .accessorySymbol: context.isCard
      case .cardTint, .cardImage: !context.isCard
      }
    }

    /// The rule the declaration falls foul of, stated positively.
    var rule: String {
      switch self {
      case .gridWrap, .gridTile: "`\(rawValue)` renders only in a grid"
      case .imageShape: "only plain and condensed tiles have a shape"
      case .accessorySymbol: "card tiles have no accessory slot"
      case .cardTint, .cardImage: "the card properties render only under `gridTile: 'card'`"
      }
    }

    var hint: String {
      switch self {
      case .gridWrap, .gridTile:
        "Declare `display: 'grid'` on the section or the page, or drop `\(rawValue)`."
      case .imageShape:
        "Render the items as plain or condensed grid tiles, or drop `imageShape`."
      case .accessorySymbol:
        "Use `gridTile: 'plain'` or `'condensed'`, or drop `accessorySymbol`."
      case .cardTint, .cardImage:
        "Declare `display: 'grid'` with `gridTile: 'card'`, or drop `\(rawValue)`."
      }
    }
  }

  /// Iterated per section; `allCases` is computed, so read it once.
  private static let allProperties = Property.allCases

  /// The rest of the block's keys, dispositioned rather than ruled on:
  /// `artworkRendering` has no structural inertness condition (artwork renders
  /// in every layout), and `display` is positional — inert only in the one
  /// position that opens no page (see `playableDisplayFinding`). Declared here
  /// so the spec-level completeness test can hold this file to the whole
  /// vocabulary: every key of the block is a rule, always renderable, or
  /// positional, and a newly added property is a decision rather than an
  /// omission.
  static let alwaysRenderable = ["artworkRendering"]
  static let positional = ["display"]

  /// A section's effective container context: the two container values every
  /// inertness condition is stated against.
  private struct Context {
    let isGrid: Bool
    let tile: GridTile

    /// Folded through `StyleResolver` rather than re-deriving the scope
    /// override: the diagnostic predicts what a renderer will do with these
    /// declarations, so it has to resolve them the way the renderers do.
    init(section: SectionStyle?, page: SectionStyle?) {
      let folded = StyleResolver.sectionStyle(section: section, page: page)
      isGrid = folded.display == .grid
      tile = folded.gridTile ?? .plain
    }

    /// Whether the card treatment is what this container renders — the
    /// condition the `card*` family is named after.
    var isCard: Bool { isGrid && tile == .card }

    /// The effective style, for the message — in the wire spelling the author
    /// types (`stringValue` is the generated enum's own mapping). `gridTile`
    /// is quoted only where a grid gives it meaning: naming it on a list would
    /// be advice to fix the wrong property.
    var described: String {
      isGrid ? "display 'grid', gridTile '\(tile.stringValue)'" : "display 'list'"
    }
  }

  /// Every inert declaration on a resolved page, as ready-to-log messages —
  /// pure, so the whole rule set is testable off-device; the caller logs.
  ///
  /// - Parameters:
  ///   - path: the resolved path, named in every message.
  ///   - pageStyle: the page's own block (`ResolvedTrack.style`).
  ///   - sections: the page's normalized sections, blocks *unfolded* — the
  ///     diagnostic attributes each finding to the level that declared it, so
  ///     it must see the levels before `StyleResolver` merges them.
  static func findings(path: String, pageStyle: SectionStyle?, sections: [Section]) -> [String] {
    var findings: [String] = []
    // A page-level declaration is judged across the whole page: it is live if
    // it renders in any section it reaches, however many others ignore it.
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
          // The section takes the page's value (nothing closer overrode it).
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

  /// `display` is positional, so it is never inert *in* a container — but on
  /// a track that renders playable (`src` wins the rendering) it promises the
  /// layout of a page that never opens.
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

  /// The one message skeleton — every finding names the page, the declaration,
  /// who declared it, why it can't render, and what to do about it.
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

  // Nitro flattens the spec's `extends`, so the two blocks share no type —
  // hence two lookups over the same key set rather than one protocol.

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
    // Container properties have no Track slot to be declared in.
    case .gridWrap, .gridTile: return false
    case .imageShape: return style.imageShape != nil
    case .accessorySymbol: return style.accessorySymbol != nil
    case .cardTint: return style.cardTint != nil
    case .cardImage: return style.cardImage != nil
    }
  }
}
