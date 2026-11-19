#if canImport(NitroModules)
  import NitroModules
#endif

/// A holder of tracks stamped with `favorited`: the page on screen, the queue.
@MainActor
protocol FavoriteStamped: AnyObject {
  /// Replaces, in place, each track `restamp` returns a track for.
  func restampFavorites(_ restamp: (Track) -> Track?)
}

/// The favorite set, with every stamped copy of a track kept in line with it. A change, from
/// `setFavorites` or a heart toggle, restamps `content`, then `queue`, then emits `changed`,
/// whose listeners read both.
@MainActor
final class Favorites {
  private var favorites = FavoriteSet()
  private weak var content: (any FavoriteStamped)?
  weak var queue: (any FavoriteStamped)?
  /// Emitted on any change to the favorites, however it came about.
  let changed = Emitter<Void>()

  init(content: any FavoriteStamped) {
    self.content = content
  }

  /// Whether favoriting is on (the player's `favorite` capability).
  var enabled: Bool {
    get { favorites.enabled }
    set { favorites.enabled = newValue }
  }

  func replace(with identities: [String]) {
    apply(favorites.replace(with: identities))
  }

  /// A local toggle; the next `replace(with:)` overrides it. Restamps `identity` even when the
  /// set already agreed, since a copy may carry the opposite value from the API.
  func set(_ identity: String, favorited: Bool) {
    favorites.set(identity, favorited: favorited)
    apply([identity])
  }

  func hydrate(_ track: Track) -> Track {
    favorites.hydrate(track)
  }

  func hydrate(_ page: ResolvedTrack) -> ResolvedTrack {
    favorites.hydrate(page)
  }

  private func apply(_ changed: Set<String>) {
    guard !changed.isEmpty else { return }
    let set = favorites
    let restamp = { (track: Track) in set.restamp(track, changed: changed) }
    content?.restampFavorites(restamp)
    queue?.restampFavorites(restamp)
    self.changed.emit(())
  }
}

extension Player: FavoriteStamped {
  func restampFavorites(_ restamp: (Track) -> Track?) {
    replaceEach(restamp)
  }
}
