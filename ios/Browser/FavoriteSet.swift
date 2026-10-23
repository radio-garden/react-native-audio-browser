#if canImport(NitroModules)
  import NitroModules
#endif

/// The favorited track identities (id when non-blank, else src), and whether favoriting is on:
/// the player's `favorite` capability. Hydrates a track's `favorited` from them.
struct FavoriteSet {
  private var identities: Set<String> = []
  /// Off, tracks are left as they are: no row hearts.
  var enabled = false

  /// Replaces the identities with `favorites`; false when that changes nothing.
  mutating func replace(with favorites: [String]) -> Bool {
    let replaced = Set(favorites)
    guard replaced != identities else { return false }
    identities = replaced
    return true
  }

  /// Reflects a local favorite toggle until the consumer's next `replace(with:)`, which
  /// reconciles the set to its canonical one.
  mutating func set(_ identity: String, favorited: Bool) {
    if favorited {
      identities.insert(identity)
    } else {
      identities.remove(identity)
    }
  }

  /// `track` with `favorited` set to true or false, so a track that is not favorited still shows
  /// an empty heart. Unchanged when favoriting is off, when the track has no identity, or when it
  /// already carries `favorited`: a value from the API or the consumer wins, as on Android and web.
  func hydrate(_ track: Track) -> Track {
    // `identity` bridges a std::string out of the C++ track, so it is read once.
    guard enabled, track.favorited == nil, let identity = track.identity else { return track }
    return track.copying(favorited: identities.contains(identity))
  }

  /// Every track of a normalized page (ADR 0010) hydrated.
  func hydrate(_ page: ResolvedTrack) -> ResolvedTrack {
    guard let sections = page.sections else { return page }
    return page.copying(sections: sections.map { section in
      section.copying(children: section.children.map(hydrate))
    })
  }
}
