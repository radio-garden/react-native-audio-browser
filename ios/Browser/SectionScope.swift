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

  /// The queue a tap on the contextual `path` plays: the playable tracks of the tapped section,
  /// at the tapped copy when the stamped index still pins it, else at the first identity match;
  /// only that track when `singleTrack`. Nil when the identity is gone from the page or the
  /// tapped track is not playable, so the caller falls back to the stored track.
  static func queue(
    in sections: [Section],
    path: String,
    singleTrack: Bool,
  ) -> (tracks: [Track], selectedIndex: Int)? {
    guard let trackId = BrowserPathHelper.extractTrackId(path),
          let scoped = scoped(in: sections, containing: trackId, tappedIndex: BrowserPathHelper.extractIndex(path))
    else { return nil }
    let children = scoped.section.children
    let playable = children.filter(\.isPlayable)
    let selectedIndex: Int
    if let offset = scoped.tappedOffset, children[offset].isPlayable {
      selectedIndex = children.prefix(through: offset).count(where: \.isPlayable) - 1
    } else if let index = playable.firstIndex(where: { $0.identity == trackId }) {
      selectedIndex = index
    } else {
      return nil
    }
    return singleTrack ? ([playable[selectedIndex]], 0) : (playable, selectedIndex)
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
