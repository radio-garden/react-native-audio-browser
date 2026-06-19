import Foundation

/// Normalized "what did the user ask to play", derived from a media intent.
/// Deliberately free of `Intents` types so the core (`HybridAudioBrowser`)
/// never imports the Intents framework — the mapping lives in the ObjC-adjacent
/// `RNABMediaIntentHandler`.
public struct MediaIntentCriteria: Sendable {
  let query: String
  let hasReference: Bool
  let hasGenres: Bool
  let hasMediaType: Bool
  /// True when `query` is effectively the host app's own name. Siri turns
  /// "Play «app»" into a search for a word in the app name — e.g. "Play Radio
  /// Garden" arrives as mediaName "Garden" (+ mediaType radio). That's an
  /// app-open/resume, not a station search.
  let matchesAppName: Bool

  // Structured search payload for the search branch, mirroring the shared
  // `SearchParams`. Kept as plain Sendable strings so this type stays in the
  // unit-testable target; the Nitro `SearchParams` is assembled in the funnel.
  /// `SearchMode` name — "genre"/"artist"/"album"/"song"/"playlist", or nil.
  let searchMode: String?
  let genre: String?
  let artist: String?
  let album: String?
  let title: String?
  let playlist: String?

  /// New search fields default to nil so existing call sites (and the resume
  /// branch, which ignores them) stay unaffected.
  init(
    query: String,
    hasReference: Bool,
    hasGenres: Bool,
    hasMediaType: Bool,
    matchesAppName: Bool,
    searchMode: String? = nil,
    genre: String? = nil,
    artist: String? = nil,
    album: String? = nil,
    title: String? = nil,
    playlist: String? = nil
  ) {
    self.query = query
    self.hasReference = hasReference
    self.hasGenres = hasGenres
    self.hasMediaType = hasMediaType
    self.matchesAppName = matchesAppName
    self.searchMode = searchMode
    self.genre = genre
    self.artist = artist
    self.album = album
    self.title = title
    self.playlist = playlist
  }

  var isResume: Bool {
    let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
    // "Play «app»": no search term and no other filter.
    if q.isEmpty { return !hasReference && !hasGenres && !hasMediaType }
    // "Play «app-name»": resume — unless there's a real filter (genre/reference)
    // that signals an actual search. mediaType is ignored here because the app
    // name itself ("Radio …") is what made Siri attach a radio media type.
    return matchesAppName && !hasReference && !hasGenres
  }

  /// Builds criteria from the raw fields of a media-search intent. Pure (no
  /// `Intents`/`Bundle` dependency) so the whole Siri-phrase → search decision —
  /// including the structured `mode`/`genre`/… mapping — is unit-testable.
  ///
  /// `mediaTypeKind` is the `INMediaItemType` distilled to "song"/"playlist"
  /// (the two types iOS expresses via `mediaType` + `mediaName` rather than a
  /// dedicated field); other types map to nil. `appName` is the host's display
  /// name (nil if unknown).
  static func from(
    mediaName: String?,
    genreNames: [String],
    artistName: String?,
    albumName: String?,
    mediaTypeKind: String?,
    hasReference: Bool,
    hasMediaType: Bool,
    appName: String?
  ) -> MediaIntentCriteria {
    let name = (mediaName ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    let trimmedNonEmpty: (String?) -> String? = {
      let t = ($0 ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
      return t.isEmpty ? nil : t
    }

    let genre = genreNames.isEmpty ? nil : genreNames.joined(separator: " ")
    let artist = trimmedNonEmpty(artistName)
    let album = trimmedNonEmpty(albumName)
    let title = mediaTypeKind == "song" ? (name.isEmpty ? nil : name) : nil
    let playlist = mediaTypeKind == "playlist" ? (name.isEmpty ? nil : name) : nil

    // `q` is always populated so search works even before the API honours
    // `mode`: prefer the spoken name, else fall back to the structured value.
    let query = name.isEmpty ? (genre ?? artist ?? album ?? title ?? playlist ?? "") : name

    // The structured focus, if any. Genre wins (the dominant radio case), then
    // album (more specific than artist), then artist, then song/playlist.
    let searchMode: String? =
      genre != nil ? "genre"
      : album != nil ? "album"
      : artist != nil ? "artist"
      : (mediaTypeKind == "song" || mediaTypeKind == "playlist") ? mediaTypeKind
      : nil

    return MediaIntentCriteria(
      query: query,
      hasReference: hasReference,
      hasGenres: !genreNames.isEmpty,
      hasMediaType: hasMediaType,
      matchesAppName: queryMatchesAppName(query, appName: appName),
      searchMode: searchMode,
      genre: genre,
      artist: artist,
      album: album,
      title: title,
      playlist: playlist
    )
  }

  /// Whether `query` is effectively the host app's own name — so "Play «app»"
  /// (which Siri delivers as a search for a word from the app name) is treated
  /// as resume rather than a station search. Case- and diacritic-insensitive.
  private static func queryMatchesAppName(_ query: String, appName: String?) -> Bool {
    guard let appName else { return false }
    let normalize: (String) -> String = {
      $0.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
        .trimmingCharacters(in: .whitespacesAndNewlines)
    }
    let q = normalize(query), a = normalize(appName)
    guard !q.isEmpty, !a.isEmpty else { return false }
    return a.contains(q) || q.contains(a)
  }
}
