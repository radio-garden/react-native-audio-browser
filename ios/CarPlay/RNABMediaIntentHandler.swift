import Foundation
import Intents
import os.log

/// Handles INPlayMediaIntent for Siri voice search (e.g. from CarPlay).
///
/// The class is internal (not public) to avoid exposing Intents types in the
/// generated AudioBrowser-Swift.h header, but is registered with the ObjC
/// runtime via `@objc(RNABMediaIntentHandler)` for NSClassFromString lookup.
///
/// Host apps call the static method from `application(_:handle:completionHandler:)`:
/// ```swift
/// @objc private protocol RNABMediaIntentHandling {
///   static func handleMediaIntent(_ intent: INIntent, completionHandler: @escaping (INIntentResponse) -> Void)
/// }
///
/// func application(_ application: UIApplication, handle intent: INIntent, completionHandler: @escaping (INIntentResponse) -> Void) {
///   (NSClassFromString("RNABMediaIntentHandler") as? RNABMediaIntentHandling.Type)?
///     .handleMediaIntent(intent, completionHandler: completionHandler)
/// }
/// ```
@objc(RNABMediaIntentHandler)
class RNABMediaIntentHandler: NSObject, INPlayMediaIntentHandling {
  private static let logger = Logger(subsystem: "com.audiobrowser", category: "MediaIntentHandler")

  // MARK: - Static Entry Point

  /// Convenience entry point for host apps. Handles the full INPlayMediaIntent flow:
  /// type checking, search, queue, and playback.
  @objc static func handleMediaIntent(_ intent: INIntent, completionHandler: @escaping @Sendable (INIntentResponse) -> Void) {
    guard let playIntent = intent as? INPlayMediaIntent else {
      completionHandler(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
      return
    }
    let handler = RNABMediaIntentHandler()
    handler.handle(intent: playIntent) { response in
      completionHandler(response)
    }
  }

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

    guard let browser = HybridAudioBrowser.shared else {
      completion(INPlayMediaIntentResponse(code: .failureRequiringAppLaunch, userActivity: nil))
      return
    }
    browser.handlePlayMediaIntent(criteria: criteria) { success in
      completion(INPlayMediaIntentResponse(code: success ? .success : .failure, userActivity: nil))
    }
  }
}
