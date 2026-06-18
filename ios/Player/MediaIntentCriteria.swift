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

  /// No actionable criteria at all → "just play / resume".
  var isResume: Bool {
    query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
      && !hasReference && !hasGenres && !hasMediaType
  }
}
