// Queue scope is the tapped section, not the whole page (ADR 0006): the
// playback context a listener expects is the list they tapped in — a page
// aggregating several sections must not leak next/previous across them.
enum SectionScope {
  enum Section {
    /// The tapped id lives inside an image row's items.
    case imageRow([ImageRowItem])
    /// The contiguous `groupTitle` run around the tapped child (items with
    /// no group title form runs of their own).
    case run([Track])
  }

  /// The section of `children` containing the playable `trackId` (a track
  /// identity: id when non-blank, else src), or nil when not found.
  static func section(of children: [Track], containing trackId: String) -> Section? {
    for child in children {
      if let items = child.imageRow, items.contains(where: { $0.identity == trackId }) {
        return .imageRow(items)
      }
    }
    guard let index = children.firstIndex(where: { $0.identity == trackId }) else {
      return nil
    }
    let group = children[index].groupTitle
    var start = index
    while start > 0, children[start - 1].groupTitle == group {
      start -= 1
    }
    var end = index
    while end + 1 < children.count, children[end + 1].groupTitle == group {
      end += 1
    }
    return .run(Array(children[start ... end]))
  }
}
