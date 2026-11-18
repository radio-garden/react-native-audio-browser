enum TabBarEntries {
  /// Whether two tab lists produce the same CarPlay tab-bar entries, so a re-query that
  /// changes nothing the bar shows emits no `tabsChanged` and keeps the selection. Compares
  /// title, path, artwork, the artwork source's uri and `disabled`; not the style, artwork
  /// headers or other track fields that `applyTabBarEntry`'s artwork load (and its resolve
  /// transform) can read.
  static func same(_ old: [Track]?, _ new: [Track]) -> Bool {
    guard let old, old.count == new.count else { return false }
    return zip(old, new).allSatisfy {
      $0.title == $1.title && $0.path == $1.path && $0.artwork == $1.artwork
        && $0.artworkSource?.uri == $1.artworkSource?.uri
        // `showTabBar` drops disabled tabs, so flipping `disabled` changes which tabs exist.
        && $0.disabled == $1.disabled
    }
  }
}
