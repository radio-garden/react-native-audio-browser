/// Compares the tab-list fields the CarPlay tab bar actually renders.
enum TabBarEntries {
  /// Whether two tab lists produce identical tab-bar entries — title, url,
  /// and the artwork inputs of `applyTabBarEntry`. Used to suppress
  /// `tabsChanged` when a tabs re-query (e.g. from `invalidateAllContent()`)
  /// resolves to the same entries, so an unchanged result never churns the
  /// tab bar or resets its selection.
  static func same(_ old: [Track]?, _ new: [Track]) -> Bool {
    guard let old, old.count == new.count else { return false }
    return zip(old, new).allSatisfy {
      $0.title == $1.title && $0.url == $1.url && $0.artwork == $1.artwork
        && $0.artworkSource?.uri == $1.artworkSource?.uri
    }
  }
}
