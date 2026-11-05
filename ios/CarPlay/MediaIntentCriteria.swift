import Foundation

/// What the user asked Siri to play, normalized from a media intent. Free of `Intents` types so
/// it builds in the SwiftPM target; `MediaIntentHandler` does the mapping.
public struct MediaIntentCriteria: Sendable {
  /// Mirrors `INMediaReference`: `currentlyPlaying` resumes natively, `my` searches.
  public enum Reference: Sendable { case my, currentlyPlaying, unknown }

  let query: String
  let reference: Reference
  let hasMediaType: Bool
  /// True when `query` is effectively the host app's own name. Siri turns "Play «App Name»"
  /// into a search for a word of it ("Name", with a radio media type): an app-open or resume,
  /// not a search.
  let matchesAppName: Bool

  // Structured search payload in plain strings, mirroring `SearchParams`, so this type stays in
  // the SwiftPM target; `MediaIntentPlayback` builds the Nitro `SearchParams`.
  /// The `SearchMode` name for the intent's media type, or nil. Never inferred from which
  /// filter field is set.
  let searchMode: String?
  let genre: String?
  let artist: String?
  let album: String?
  let title: String?
  let playlist: String?

  /// `mediaTypeMode` is a `SearchMode` name ("station", "podcast", "song", …), or nil for a
  /// filter-only or unclassified media type.
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

  /// Resume rather than search. `currentlyPlaying` always resumes and `my` always searches;
  /// otherwise a bare "play" resumes unless a genre or media type is given, and "play «app name»"
  /// resumes unless a genre is given (Siri sends it with a media type).
  var isResume: Bool {
    switch reference {
    case .currentlyPlaying: return true
    case .my: return false
    case .unknown:
      let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
      if q.isEmpty { return genre == nil && !hasMediaType }
      return matchesAppName && genre == nil
    }
  }

  /// Whole-word subset match in either direction, case- and diacritic-insensitive. Siri may
  /// deliver only part of the name, so a partial match counts; a substring match would swallow
  /// any query inside the app name and make one-word stations unreachable by voice.
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
