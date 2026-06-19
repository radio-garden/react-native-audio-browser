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
  /// `Intents`/`Bundle` dependency) so the whole Siri-phrase → search decision
  /// is unit-testable. `appName` is the host app's display name (nil if unknown).
  static func from(
    mediaName: String?,
    genreNames: [String],
    hasReference: Bool,
    hasMediaType: Bool,
    appName: String?
  ) -> MediaIntentCriteria {
    let name = (mediaName ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    // Siri routes a genre ("jazz", "classical") into genreNames, not mediaName.
    // Fall back to the genre text so "Play jazz on …" searches for it instead of
    // firing an empty query.
    let query = name.isEmpty ? genreNames.joined(separator: " ") : name
    return MediaIntentCriteria(
      query: query,
      hasReference: hasReference,
      hasGenres: !genreNames.isEmpty,
      hasMediaType: hasMediaType,
      matchesAppName: queryMatchesAppName(query, appName: appName)
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
