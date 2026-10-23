import Foundation

/// Normalized "what did the user ask to play", derived from a media intent.
/// Deliberately free of `Intents` types so the core (`HybridAudioBrowser`)
/// never imports the Intents framework — the mapping lives in the
/// `Intents`-aware `MediaIntentHandler`.
public struct MediaIntentCriteria: Sendable {
  /// Media-reference axis (mirrors `INMediaReference`). `currentlyPlaying`
  /// routes to native resume and never reaches the consumer; `my` routes to
  /// the search source; `unknown` is the default.
  public enum Reference: Sendable { case my, currentlyPlaying, unknown }

  let query: String
  let reference: Reference
  let hasMediaType: Bool
  /// True when `query` is effectively the host app's own name. Siri turns "Play «App Name»"
  /// into a search for a word of it ("Name", with a radio media type): an app-open or resume,
  /// not a search.
  let matchesAppName: Bool

  // Structured search payload, mirroring `SearchParams` in plain strings so this type stays in
  // the SwiftPM target; the funnel builds the Nitro `SearchParams`.
  /// The container-vertical `SearchMode` name, or nil. Comes only from the intent's media type,
  /// never from which filter field is set.
  let searchMode: String?
  let genre: String?
  let artist: String?
  let album: String?
  let title: String?
  let playlist: String?

  /// Builds criteria from the raw fields of a media-search intent.
  ///
  /// `mediaTypeMode` is the `SearchMode` string for the intent's container vertical ("station",
  /// "podcast", "song", …), or nil for a filter-only or unclassified type.
  init(
    mediaName: String?,
    genreNames: [String],
    artistName: String?,
    albumName: String?,
    mediaTypeMode: String?,
    reference: Reference,
    hasMediaType: Bool,
    appName: String?,
  ) {
    let name = (mediaName ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    let trimmedNonEmpty: (String?) -> String? = {
      let t = ($0 ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
      return t.isEmpty ? nil : t
    }

    genre = genreNames.isEmpty ? nil : genreNames.joined(separator: " ")
    artist = trimmedNonEmpty(artistName)
    album = trimmedNonEmpty(albumName)
    // song/playlist carry their spoken name into a dedicated field.
    title = mediaTypeMode == "song" ? (name.isEmpty ? nil : name) : nil
    playlist = mediaTypeMode == "playlist" ? (name.isEmpty ? nil : name) : nil

    // Always populated, so search works before the API honours `mode`: the spoken name, else a
    // structured value.
    query = name.isEmpty ? (genre ?? artist ?? album ?? title ?? playlist ?? "") : name
    self.reference = reference
    self.hasMediaType = hasMediaType
    matchesAppName = Self.queryMatchesAppName(query, appName: appName)
    searchMode = mediaTypeMode
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
      if q.isEmpty { return genre == nil && !hasMediaType }
      // "Play «app-name»": resume — unless a real filter signals a search.
      return matchesAppName && genre == nil
    }
  }

  /// Whether `query` is effectively the host app's own name. Case- and
  /// diacritic-insensitive.
  ///
  /// Whole-word subset match in either direction. Siri may deliver only part of
  /// the name ("Name" for «App Name»), so a partial match must count — but a
  /// substring match swallowed any query appearing inside the app name, making
  /// one-word stations unreachable by voice.
  private static func queryMatchesAppName(_ query: String, appName: String?) -> Bool {
    guard let appName else { return false }
    let tokens: (String) -> Set<String> = {
      Set(
        $0.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
          .split(whereSeparator: { !$0.isLetter && !$0.isNumber })
          .map(String.init),
      )
    }
    let q = tokens(query), a = tokens(appName)
    guard !q.isEmpty, !a.isEmpty else { return false }
    return q.isSubset(of: a) || a.isSubset(of: q)
  }
}
