import Foundation
import Intents
import os.log

/// In-app `INAddMediaIntent` handler — "Hey Siri, add this to my favorites."
/// Adding to the user's collection maps onto the library-managed favorite state
/// of the **currently playing** track via `setActiveTrackFavorited` — the same
/// path as the CarPlay favorite button and the `INUpdateMediaAffinityIntent`
/// "like", so it flips the now-playing heart and fires `onFavoriteChanged` for
/// the consumer to persist. No extra config beyond the `favorite` capability.
///
/// We expose a single flat favorites collection (no named playlists), so the
/// destination always resolves to the user's library. Adding a *named* item
/// that isn't currently playing needs the structured-intent consumer callback
/// and is out of scope here — like the named play-by-search case.
///
/// Internal (keeps Intents types out of the generated header), exposed to the
/// ObjC runtime via `@objc(RNABMediaAddHandler)` and vended from
/// `RNABAudioBrowser.handlerForIntent(_:)`.
@objc(RNABMediaAddHandler)
class RNABMediaAddHandler: NSObject, INAddMediaIntentHandling {
  private static let logger = Logger(subsystem: "com.audiobrowser", category: "MediaAddHandler")

  func handle(
    intent _: INAddMediaIntent,
    completion: @escaping @Sendable (INAddMediaIntentResponse) -> Void,
  ) {
    Self.logger.info("Add media — favorite current track")

    Task { @MainActor in
      let applied = HybridAudioBrowser.favoriteCurrentTrack(true)
      completion(INAddMediaIntentResponse(code: applied ? .success : .failure, userActivity: nil))
    }
  }

  // Siri runs a resolve phase before `handle`; without a resolution method it
  // errors ("Unable to find implementation of resolution method"). These are
  // *optional* @objc protocol methods, so pin the exact selectors SiriKit
  // dispatches to (otherwise they aren't exposed and are "not found"). We act on
  // the currently-playing track / our single library, so pass both through.
  @objc(resolveMediaItemsForAddMedia:withCompletion:)
  func resolveMediaItems(
    for intent: INAddMediaIntent,
    with completion: @escaping ([INAddMediaMediaItemResolutionResult]) -> Void,
  ) {
    let name = intent.mediaSearch?.mediaName ?? ""
    let item = INMediaItem(identifier: name, title: name, type: .unknown, artwork: nil)
    completion([.success(with: item)])
  }

  @objc(resolveMediaDestinationForAddMedia:withCompletion:)
  func resolveMediaDestination(
    for _: INAddMediaIntent,
    with completion: @escaping (INAddMediaMediaDestinationResolutionResult) -> Void,
  ) {
    // One flat favorites collection — always the user's library, no playlists.
    completion(.success(with: INMediaDestination.library))
  }
}
