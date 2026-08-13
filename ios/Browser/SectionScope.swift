// Queue scope is the tapped section, not the whole page (ADR 0006): the
// playback context a listener expects is the list they tapped in — a page
// aggregating several sections must not leak next/previous across them.
enum SectionScope {
  enum Section {
    /// The tapped id lives inside an image row's items.
    case imageRow([ImageRowItem])
    /// The contiguous `groupTitle` run around the tapped child (items with
    /// no group title form runs of their own). `tappedOffset` is the tapped
    /// child's offset within the run — non-nil only when the stamped index
    /// pinned the exact copy.
    case run([Track], tappedOffset: Int?)
  }

  /// The section of `children` containing the playable `trackId` (a track
  /// identity: id when non-blank, else src), or nil when not found.
  ///
  /// `tappedIndex` — the page position stamped into the contextual URL — is a
  /// tie-breaker, never an identifier: when the child at that position still
  /// carries the tapped identity (directly or in its image row), it pins which
  /// surface was tapped; when it doesn't (the list shifted), resolution falls
  /// back to the first identity match. A stale index can therefore never
  /// select a different track — at worst a different copy of the same one.
  static func section(
    of children: [Track],
    containing trackId: String,
    tappedIndex: Int? = nil,
  ) -> Section? {
    if let tappedIndex, children.indices.contains(tappedIndex) {
      let child = children[tappedIndex]
      if child.identity == trackId {
        return run(around: tappedIndex, in: children, pinned: true)
      }
      if let items = child.imageRow, items.contains(where: { $0.identity == trackId }) {
        return .imageRow(items)
      }
    }
    for child in children {
      if let items = child.imageRow, items.contains(where: { $0.identity == trackId }) {
        return .imageRow(items)
      }
    }
    guard let index = children.firstIndex(where: { $0.identity == trackId }) else {
      return nil
    }
    return run(around: index, in: children, pinned: false)
  }

  private static func run(around index: Int, in children: [Track], pinned: Bool) -> Section {
    let group = children[index].groupTitle
    var start = index
    while start > 0, children[start - 1].groupTitle == group {
      start -= 1
    }
    var end = index
    while end + 1 < children.count, children[end + 1].groupTitle == group {
      end += 1
    }
    return .run(Array(children[start ... end]), tappedOffset: pinned ? index - start : nil)
  }
}
