// Queue scope is the tapped section, not the whole page: ADR 0006.
enum SectionScope {
  /// `tappedOffset` is the tapped child's offset in `section`, non-nil only when the stamped
  /// index pinned that exact copy.
  struct Scoped {
    let section: Section
    let tappedOffset: Int?
  }

  /// The section containing the track identity `trackId`, or nil when none does.
  ///
  /// `tappedIndex` is the flat page position (children concatenated in section order) and only
  /// breaks ties: when the child there still has `trackId`, it pins the section and copy; otherwise
  /// the first section containing `trackId` wins. A stale index can at worst pick another copy of
  /// the same track, never a different track.
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

  /// The queue a tap on the contextual `path` plays: the tapped section's playable tracks, at the
  /// pinned copy, else the first identity match; only that track when `singleTrack`. Nil when the
  /// identity is gone from the page or the tapped track is not playable, so the caller falls back
  /// to the stored track.
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
  /// `sections` when present, else plain `children` as one untitled section.
  var normalizedSections: [Section]? {
    if let sections { return sections }
    guard let children else { return nil }
    return [.untitled(children)]
  }

  /// Children concatenated in section order: the order contextual `__index` positions count in.
  var flattenedChildren: [Track]? {
    normalizedSections.map { $0.flatMap(\.children) }
  }
}
