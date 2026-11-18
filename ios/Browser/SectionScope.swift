/// Queue scope is the tapped section, not the whole page (ADR 0006).
enum SectionScope {
  struct Scoped {
    let section: Section
    /// The tapped child's offset in `section`; set only when the stamped index pinned that copy.
    let tappedOffset: Int?
  }

  /// The section containing the track identity `trackId`, or nil when none does. `tappedIndex`, the
  /// flat page position, only breaks ties: when the child there still has `trackId`, it pins the
  /// section and copy; otherwise the first section containing `trackId` wins (ADR 0009).
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
  /// section holds no playable track with that identity, or the identity is gone from the page.
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
  /// `sections` when present, else `children` as one untitled section.
  var normalizedSections: [Section]? {
    if let sections { return sections }
    guard let children else { return nil }
    return [.untitled(children)]
  }

  /// Children concatenated in section order: the order a Contextual Path's `__index` counts in.
  var flattenedChildren: [Track]? {
    normalizedSections.map { $0.flatMap(\.children) }
  }
}
