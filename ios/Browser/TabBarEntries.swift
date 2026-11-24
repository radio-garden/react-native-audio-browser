enum TabBarEntries {
  enum Image: Equatable {
    case sfSymbol(artwork: String)
    case asset(name: String)
    case fetch
    case none
  }

  /// Where a tab's image comes from. Local artwork is set as the tab bar is built, never
  /// fetched, even when the tab also has an `artworkSource`.
  static func image(for tab: Track) -> Image {
    if let artwork = tab.artwork?.url, let local = LocalArtwork(artwork) {
      switch local {
      case .sfSymbol: return .sfSymbol(artwork: artwork)
      case let .asset(name): return .asset(name: name)
      }
    }
    return tab.artwork != nil || tab.artworkSource != nil ? .fetch : .none
  }

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

  /// What `tabsChanged` emits when the tabs go from `old` to `new`, nil for no event. Tabs that
  /// go away emit an empty list, as on Android: the event cannot carry nil.
  static func change(from old: [Track]?, to new: [Track]?) -> [Track]? {
    guard let new else { return old == nil ? nil : [] }
    return same(old, new) ? nil : new
  }
}
