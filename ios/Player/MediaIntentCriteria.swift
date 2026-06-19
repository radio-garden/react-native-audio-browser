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
}
