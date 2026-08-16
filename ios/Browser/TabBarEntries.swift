/// Compares the tab-list fields the CarPlay tab bar actually renders.
enum TabBarEntries {
  /// Whether two tab lists produce identical tab-bar entries — title, path,
  /// the artwork inputs of `applyTabBarEntry`, and `disabled`. Used to suppress
  /// `tabsChanged` when a tabs re-query (e.g. from `invalidateAllContent()`)
  /// resolves to the same entries, so an unchanged result never churns the
  /// tab bar or resets its selection.
  ///
  /// `disabled` counts even though it renders nothing itself: a disabled tab is
  /// filtered out of the tab bar entirely (`showTabBar`), so a tab flipping it
  /// with otherwise-identical fields changes which tabs exist. Omitting it here
  /// swallowed the emit, and the tab never appeared or disappeared.
  static func same(_ old: [Track]?, _ new: [Track]) -> Bool {
    guard let old, old.count == new.count else { return false }
    return zip(old, new).allSatisfy {
      $0.title == $1.title && $0.path == $1.path && $0.artwork == $1.artwork
        && $0.artworkSource?.uri == $1.artworkSource?.uri
        && $0.disabled == $1.disabled
    }
  }
}
