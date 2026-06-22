import Foundation

/// Normalized "what did the user ask to play", derived from a media intent.
/// Deliberately free of `Intents` types so the core (`HybridAudioBrowser`)
/// never imports the Intents framework — the mapping lives in the
/// `Intents`-aware `RNABMediaIntentHandler`.
public struct MediaIntentCriteria: Sendable {
  /// Media-reference axis (mirrors `INMediaReference`). `currentlyPlaying`
  /// routes to native resume and never reaches the consumer; `my` routes to
  /// the search source; `unknown` is the default.
  public enum Reference: Sendable { case my, currentlyPlaying, unknown }

  let query: String
  let reference: Reference
  let hasGenres: Bool
  let hasMediaType: Bool
  /// True when `query` is effectively the host app's own name. Siri turns
  /// "Play «app»" into a search for a word in the app name — e.g. "Play Radio
  /// Garden" arrives as mediaName "Garden" (+ radio mediaType). That's an
  /// app-open/resume, not a station search.
  let matchesAppName: Bool

  // Structured search payload, mirroring the shared `SearchParams`. Plain
  // Sendable strings so this type stays in the unit-testable target; the Nitro
  // `SearchParams` is assembled in the funnel.
  /// The container-vertical `SearchMode` name, or nil. Comes ONLY from the
  /// intent's media type — never derived from which filter field is set.
  let searchMode: String?
  let genre: String?
  let artist: String?
  let album: String?
  let title: String?
  let playlist: String?

  init(
    query: String,
    reference: Reference,
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
    self.reference = reference
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

  /// Resume vs search, by reference:
  /// - `currentlyPlaying` → always resume ("play this" plays the active track)
  /// - `my` → always search ("play my favorites" goes to the consumer)
  /// - `unknown` → the no-criteria / app-name heuristic
  var isResume: Bool {
    switch reference {
    case .currentlyPlaying: return true
    case .my: return false
    case .unknown:
      let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
      // "Play «app»": no search term and no other filter → resume.
      if q.isEmpty { return !hasGenres && !hasMediaType }
      // "Play «app-name»": resume — unless a real filter signals a search.
      return matchesAppName && !hasGenres
    }
  }

  /// Builds criteria from the raw fields of a media-search intent. Pure (no
  /// `Intents`/`Bundle` dependency) so the whole Siri-phrase → search decision
  /// is unit-testable.
  ///
  /// `mediaTypeMode` is the already-collapsed `SearchMode` string for the
  /// intent's container vertical (e.g. "station"/"podcast"/"song"), or nil for
  /// a filter-only / unclassified type. `reference` is the mapped axis.
  static func from(
    mediaName: String?,
    genreNames: [String],
    artistName: String?,
    albumName: String?,
    mediaTypeMode: String?,
    reference: Reference,
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
    // song/playlist carry their spoken name into a dedicated field.
    let title = mediaTypeMode == "song" ? (name.isEmpty ? nil : name) : nil
    let playlist = mediaTypeMode == "playlist" ? (name.isEmpty ? nil : name) : nil

    // `query` is always populated so search works even before the API honours
    // `mode`: prefer the spoken name, else fall back to a structured value.
    let query = name.isEmpty ? (genre ?? artist ?? album ?? title ?? playlist ?? "") : name

    return MediaIntentCriteria(
      query: query,
      reference: reference,
      hasGenres: !genreNames.isEmpty,
      hasMediaType: hasMediaType,
      matchesAppName: queryMatchesAppName(query, appName: appName),
      searchMode: mediaTypeMode,   // mode is the vertical, verbatim — no derivation
      genre: genre,
      artist: artist,
      album: album,
      title: title,
      playlist: playlist
    )
  }

  /// Whether `query` is effectively the host app's own name. Case- and
  /// diacritic-insensitive.
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
