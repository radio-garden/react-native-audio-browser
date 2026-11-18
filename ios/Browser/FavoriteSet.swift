#if canImport(NitroModules)
  import NitroModules
#endif

/// The favorited track identities and whether favoriting is on (the player's `favorite`
/// capability).
struct FavoriteSet {
  private var identities: Set<String> = []
  var enabled = false

  /// False when `favorites` holds the identities already there.
  mutating func replace(with favorites: [String]) -> Bool {
    let replaced = Set(favorites)
    guard replaced != identities else { return false }
    identities = replaced
    return true
  }

  /// A local toggle; the consumer's next `replace(with:)` overrides it.
  mutating func set(_ identity: String, favorited: Bool) {
    if favorited {
      identities.insert(identity)
    } else {
      identities.remove(identity)
    }
  }

  /// `track` with `favorited` true or false, so an unfavorited track shows an empty heart.
  /// Unchanged when favoriting is off, the track has no identity, or it already carries
  /// `favorited`: a value from the API or the consumer wins, as on Android and web.
  func hydrate(_ track: Track) -> Track {
    // `identity` bridges a std::string out of the C++ track, so it is read once.
    guard enabled, track.favorited == nil, let identity = track.identity else { return track }
    return track.copying(favorited: identities.contains(identity))
  }

  func hydrate(_ page: ResolvedTrack) -> ResolvedTrack {
    guard let sections = page.sections else { return page }
    return page.copying(sections: sections.map { section in
      section.copying(children: section.children.map(hydrate))
    })
  }
}
