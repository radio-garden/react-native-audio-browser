// Queue scope is the tapped section, not the whole page (ADR 0006): the
// playback context a listener expects is the list they tapped in — a page
// aggregating several sections must not leak next/previous across them.
// Sections are structural (ADR 0010), so scoping is a lookup, not a
// groupTitle-run derivation.
enum SectionScope {
  /// The section containing the tapped identity, plus the tapped child's
  /// offset within it — non-nil only when the stamped flat index pinned the
  /// exact copy.
  struct Scoped {
    let section: Section
    let tappedOffset: Int?
  }

  /// The section of `sections` containing the playable `trackId` (a track
  /// identity: id when non-blank, else src), or nil when not found.
  ///
  /// `tappedIndex` — the flat page position stamped into the contextual URL
  /// (children concatenated in section order) — is a tie-breaker, never an
  /// identifier: when the child at that position still carries the tapped
  /// identity, it pins which section (and which copy) was tapped; when it
  /// doesn't (the list shifted), resolution falls back to the first section
  /// containing the identity. A stale index can therefore never select a
  /// different track — at worst a different copy of the same one.
  static func scoped(
    in sections: [Section],
    containing trackId: String,
    tappedIndex: Int? = nil,
  ) -> Scoped? {
    if let tappedIndex, tappedIndex >= 0 {
      var start = 0
      for section in sections {
        let offset = tappedIndex - start
        if offset < section.children.count {
          if section.children[offset].identity == trackId {
            return Scoped(section: section, tappedOffset: offset)
          }
          break
        }
        start += section.children.count
      }
    }
    for section in sections {
      if section.children.contains(where: { $0.identity == trackId }) {
        return Scoped(section: section, tappedOffset: nil)
      }
    }
    return nil
  }
}

// MARK: - Page shape helpers (ADR 0010)

extension ResolvedTrack {
  /// The canonical sectioned shape: `sections` wins when present; plain
  /// `children` is authoring sugar for one untitled section.
  var normalizedSections: [Section]? {
    if let sections { return sections }
    guard let children else { return nil }
    return [.untitled(children)]
  }

  /// The page's children concatenated in section order — the flattening
  /// that defines contextual `__index` positions and the flat views (tabs,
  /// search) of a sectioned page.
  var flattenedChildren: [Track]? {
    normalizedSections.map { $0.flatMap(\.children) }
  }
}
