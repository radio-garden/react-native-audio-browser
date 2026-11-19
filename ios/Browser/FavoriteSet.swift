#if canImport(NitroModules)
  import NitroModules
#endif

/// The favorited track identities and whether favoriting is on (the player's `favorite`
/// capability).
struct FavoriteSet {
  private var identities: Set<String> = []
  var enabled = false

  /// The identities whose favorite state changed; empty when `favorites` holds those already
  /// there.
  mutating func replace(with favorites: [String]) -> Set<String> {
    let replaced = Set(favorites)
    let changed = replaced.symmetricDifference(identities)
    identities = replaced
    return changed
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

  /// `track` with `favorited` as the set has it, for a track whose identity is in `changed`
  /// (what `replace(with:)` returned, or the identity just `set`). Unlike `hydrate`, this
  /// overwrites the value the track carries. Nil when the track stands as it is.
  func restamp(_ track: Track, changed: Set<String>) -> Track? {
    guard enabled, let identity = track.identity, changed.contains(identity) else { return nil }
    let favorited = identities.contains(identity)
    return track.favorited == favorited ? nil : track.copying(favorited: favorited)
  }

  /// `page` with its tracks restamped; nil when none of them changes.
  func restamp(_ page: ResolvedTrack, changed: Set<String>) -> ResolvedTrack? {
    guard let sections = page.sections else { return nil }
    var restamped = false
    let updated = sections.map { section in
      section.copying(children: section.children.map { track in
        guard let stamped = restamp(track, changed: changed) else { return track }
        restamped = true
        return stamped
      })
    }
    return restamped ? page.copying(sections: updated) : nil
  }
}
