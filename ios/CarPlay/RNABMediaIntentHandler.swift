import Foundation
import Intents
import os.log

/// In-app INPlayMediaIntent handler (Siri / CarPlay voice). Internal so Intents
/// types stay out of the generated header; exposed to the ObjC runtime via
/// `@objc(RNABMediaIntentHandler)` and vended from `RNABAudioBrowser.handlerForIntent(_:)`.
@objc(RNABMediaIntentHandler)
class RNABMediaIntentHandler: NSObject, INPlayMediaIntentHandling {
  private static let logger = Logger(subsystem: "com.audiobrowser", category: "MediaIntentHandler")

  // MARK: - INPlayMediaIntentHandling

  func handle(intent: INPlayMediaIntent, completion: @escaping @Sendable (INPlayMediaIntentResponse) -> Void) {
    let s = intent.mediaSearch
    let mediaName = (s?.mediaName ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    let genres = s?.genreNames ?? []
    // Siri routes a genre ("jazz", "classical") into genreNames, not mediaName.
    // Fall back to the genre text so "Play jazz on …" actually searches for it
    // rather than firing an empty query.
    let query = mediaName.isEmpty ? genres.joined(separator: " ") : mediaName
    let criteria = MediaIntentCriteria(
      query: query,
      hasReference: (s?.reference ?? .unknown) != .unknown,
      hasGenres: !genres.isEmpty,
      hasMediaType: (s?.mediaType ?? .unknown) != .unknown,
      matchesAppName: Self.queryNamesHostApp(query),
    )
    Self.logger.info("Play media intent — query=\(criteria.query) matchesApp=\(criteria.matchesAppName) resume=\(criteria.isResume)")

    // Static + gate-waiting, so it works even before the shared instance exists
    // (background intent launch, RN not booted yet).
    HybridAudioBrowser.handlePlayMediaIntent(criteria: criteria) { success in
      completion(INPlayMediaIntentResponse(code: success ? .success : .failure, userActivity: nil))
    }
  }

  /// Whether `query` is effectively the host app's own name — so "Play «app»"
  /// (which Siri delivers as a search for a word from the app name) is treated
  /// as resume rather than a station search. Uses the host bundle's display name.
  private static func queryNamesHostApp(_ query: String) -> Bool {
    let info = Bundle.main.infoDictionary
    guard let appName = (info?["CFBundleDisplayName"] as? String)
            ?? (info?["CFBundleName"] as? String) else { return false }
    let normalize: (String) -> String = {
      $0.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
        .trimmingCharacters(in: .whitespacesAndNewlines)
    }
    let q = normalize(query), a = normalize(appName)
    guard !q.isEmpty, !a.isEmpty else { return false }
    return a.contains(q) || q.contains(a)
  }
}
