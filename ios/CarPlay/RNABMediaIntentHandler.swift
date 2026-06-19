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
    let criteria = MediaIntentCriteria.from(
      mediaName: s?.mediaName,
      genreNames: s?.genreNames ?? [],
      hasReference: (s?.reference ?? .unknown) != .unknown,
      hasMediaType: (s?.mediaType ?? .unknown) != .unknown,
      appName: Self.hostAppName()
    )
    Self.logger.info("Play media intent — query=\(criteria.query) matchesApp=\(criteria.matchesAppName) resume=\(criteria.isResume)")

    // Static + gate-waiting, so it works even before the shared instance exists
    // (background intent launch, RN not booted yet).
    HybridAudioBrowser.handlePlayMediaIntent(criteria: criteria) { success in
      completion(INPlayMediaIntentResponse(code: success ? .success : .failure, userActivity: nil))
    }
  }

  /// Host app's display name, used to recognise "Play «app»" as a resume.
  private static func hostAppName() -> String? {
    let info = Bundle.main.infoDictionary
    return (info?["CFBundleDisplayName"] as? String) ?? (info?["CFBundleName"] as? String)
  }
}
