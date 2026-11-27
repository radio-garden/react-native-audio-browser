// Queue scope is the tapped section, not the whole page: ADR 0006.
enum SectionScope {
  /// The page a linked path queues marks the tapped track disabled: the tap plays nothing.
  struct DisabledOnLinkedPage: Error {}

  /// `tappedOffset` is the tapped child's offset in `section`, non-nil only when the stamped
  /// index pinned that exact copy. `onlyDisabled` is set when no index pinned it and the page holds
  /// the identity only disabled.
  struct Scoped {
    let section: Section
    let tappedOffset: Int?
    var onlyDisabled = false
  }

  /// The section containing the track identity `trackId`, or nil when none does.
  ///
  /// `tappedIndex` is the flat page position (children concatenated in section order) and only
  /// breaks ties: when the child there still has `trackId`, it pins the section and copy; otherwise
  /// the first section holding an enabled copy of `trackId` wins, else the first holding any. A
  /// stale index can at worst pick another copy of the same track, never a different track.
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
    let holding = sections.filter { $0.children.contains { $0.identity == trackId } }
    if let section = holding.first(where: { $0.children.contains { $0.identity == trackId && $0.disabled != true } }) {
      return Scoped(section: section, tappedOffset: nil)
    }
    return holding.first.map { Scoped(section: $0, tappedOffset: nil, onlyDisabled: true) }
  }

  /// The queue a tap on the contextual `path` plays: the tapped section's playable tracks, at the
  /// pinned copy, else the first enabled identity match; only that track when `singleTrack`. Nil
  /// when the identity is gone from the page or the tapped track is not playable, so the caller
  /// falls back to the stored track. Throws `DisabledOnLinkedPage` when `path` is linked and the page holds the
  /// identity only disabled.
  static func queue(
    in sections: [Section],
    path: String,
    singleTrack: Bool,
  ) throws -> (tracks: [Track], selectedIndex: Int)? {
    guard let trackId = BrowserPathHelper.extractTrackId(path),
          let scoped = scoped(in: sections, containing: trackId, tappedIndex: BrowserPathHelper.extractIndex(path))
    else { return nil }
    if scoped.onlyDisabled, BrowserPathHelper.extractOrigin(path) != nil {
      throw DisabledOnLinkedPage()
    }
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

extension SectionScope {
  /// The queue a tap on the contextual `path` plays, as `expand` gives it for a path, and the page
  /// it came from. A `.path`-queued section's child that its linked page does not queue (the page
  /// fails, or no longer holds it) queues its own section on the page it was tapped on, which is
  /// then the source; one the page disables throws `DisabledOnLinkedPage` instead.
  @MainActor
  static func expanding(
    _ path: String,
    with expand: (String) async throws -> (tracks: [Track], selectedIndex: Int)?,
  ) async throws -> (tracks: [Track], selectedIndex: Int, sourcePath: String)? {
    guard let origin = BrowserPathHelper.extractOrigin(path) else {
      return try await expand(path).map { ($0.tracks, $0.selectedIndex, BrowserPathHelper.stripTrackId(path)) }
    }
    do {
      if let linked = try await expand(path) {
        return (linked.tracks, linked.selectedIndex, BrowserPathHelper.stripTrackId(path))
      }
    } catch let error as DisabledOnLinkedPage {
      throw error
    } catch {}
    try Task.checkCancellation()
    return try await expand(origin).map { ($0.tracks, $0.selectedIndex, BrowserPathHelper.stripTrackId(origin)) }
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
