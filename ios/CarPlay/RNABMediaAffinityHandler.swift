import Foundation
import Intents
import os.log

/// In-app `INUpdateMediaAffinityIntent` handler — "Hey Siri, I like / don't like
/// this." Maps the affinity onto the library-managed favorite state of the
/// **currently playing** track via `setActiveTrackFavorited` — the same path as
/// the CarPlay favorite button — so it flips the now-playing heart and fires
/// `onFavoriteChanged` for the consumer to persist. Like → favorited, dislike →
/// unfavorited. No extra config beyond the `favorite` capability.
///
/// Internal (keeps Intents types out of the generated header), exposed to the
/// ObjC runtime via `@objc(RNABMediaAffinityHandler)` and vended from
/// `RNABAudioBrowser.handlerForIntent(_:)`.
///
/// Affinity for a *named* item ("I like «station»") is out of scope here — that
/// needs the structured-intent consumer callback.
@objc(RNABMediaAffinityHandler)
class RNABMediaAffinityHandler: NSObject, INUpdateMediaAffinityIntentHandling {
  private static let logger = Logger(subsystem: "com.audiobrowser", category: "MediaAffinityHandler")

  func handle(
    intent: INUpdateMediaAffinityIntent,
    completion: @escaping @Sendable (INUpdateMediaAffinityIntentResponse) -> Void,
  ) {
    let affinity = intent.affinityType
    Self.logger.info("Update media affinity — type=\(affinity.rawValue)")

    Task { @MainActor in
      // Affinity targets the current track; if nothing is playing there's nothing
      // to like, so the assistant reports failure rather than a silent no-op.
      guard let browser = HybridAudioBrowser.shared,
            browser.getPlayer()?.currentTrack != nil
      else {
        completion(INUpdateMediaAffinityIntentResponse(code: .failure, userActivity: nil))
        return
      }

      // Map affinity onto the library-managed favorite state (same path as the
      // CarPlay favorite button): flips the now-playing heart and fires
      // `onFavoriteChanged` so the consumer persists it — no extra handler.
      switch affinity {
      case .like:
        try? browser.setActiveTrackFavorited(favorited: true)
      case .dislike:
        try? browser.setActiveTrackFavorited(favorited: false)
      default:
        completion(INUpdateMediaAffinityIntentResponse(code: .failure, userActivity: nil))
        return
      }
      completion(INUpdateMediaAffinityIntentResponse(code: .success, userActivity: nil))
    }
  }

  // Siri runs a resolve phase before `handle`; without a resolution method it
  // errors ("Unable to find implementation of resolution method"). This is an
  // *optional* @objc protocol method, so pin the exact selector SiriKit dispatches
  // to (otherwise it isn't exposed and is "not found"). We act on the currently
  // playing track regardless, so pass the spoken item straight through.
  @objc(resolveMediaItemsForUpdateMediaAffinity:withCompletion:)
  func resolveMediaItems(
    for intent: INUpdateMediaAffinityIntent,
    with completion: @escaping ([INUpdateMediaAffinityMediaItemResolutionResult]) -> Void,
  ) {
    let name = intent.mediaSearch?.mediaName ?? ""
    let item = INMediaItem(identifier: name, title: name, type: .unknown, artwork: nil)
    completion([.success(with: item)])
  }
}
