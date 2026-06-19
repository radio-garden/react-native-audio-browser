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
    let criteria = MediaIntentCriteria(
      query: s?.mediaName ?? "",
      hasReference: (s?.reference ?? .unknown) != .unknown,
      hasGenres: !((s?.genreNames ?? []).isEmpty),
      hasMediaType: (s?.mediaType ?? .unknown) != .unknown,
    )
    Self.logger.info("Play media intent — query=\(criteria.query) resume=\(criteria.isResume)")

    // Static + gate-waiting, so it works even before the shared instance exists
    // (background intent launch, RN not booted yet).
    HybridAudioBrowser.handlePlayMediaIntent(criteria: criteria) { success in
      completion(INPlayMediaIntentResponse(code: success ? .success : .failure, userActivity: nil))
    }
  }
}
