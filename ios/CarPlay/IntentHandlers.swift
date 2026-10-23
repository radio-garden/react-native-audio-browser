import Foundation
import Intents
import os.log

/// The library's Siri handlers, vended to the host app through `RNABAudioBrowser.handlerForIntent:`.
@objc(RNABIntentHandlers)
public final class IntentHandlers: NSObject {
  /// A handler for `INPlayMediaIntent`, `INUpdateMediaAffinityIntent` and `INAddMediaIntent`;
  /// nil for any other intent.
  @objc(handlerForIntent:)
  public static func handler(for intent: INIntent) -> Any? {
    switch intent {
    case is INPlayMediaIntent: MediaIntentHandler()
    case is INUpdateMediaAffinityIntent, is INAddMediaIntent: FavoriteIntentHandler()
    default: nil
    }
  }
}

/// "Add this to my favorites" and "I like / don't like this": sets the favorite state of the
/// playing track, through the same path as the CarPlay favorite button. A named item that is
/// not playing is out of scope.
final class FavoriteIntentHandler: NSObject, INAddMediaIntentHandling, INUpdateMediaAffinityIntentHandling {
  private static let logger = Logger(subsystem: "com.audiobrowser", category: "FavoriteIntentHandler")

  func handle(intent _: INAddMediaIntent, completion: @escaping @Sendable (INAddMediaIntentResponse) -> Void) {
    Self.logger.info("Add media — favorite current track")
    Task { @MainActor in
      let applied = Self.favoriteCurrentTrack(true)
      completion(INAddMediaIntentResponse(code: applied ? .success : .failure, userActivity: nil))
    }
  }

  func handle(
    intent: INUpdateMediaAffinityIntent,
    completion: @escaping @Sendable (INUpdateMediaAffinityIntentResponse) -> Void,
  ) {
    let affinity = intent.affinityType
    Self.logger.info("Update media affinity — type=\(affinity.rawValue)")
    Task { @MainActor in
      let applied = switch affinity {
      case .like: Self.favoriteCurrentTrack(true)
      case .dislike: Self.favoriteCurrentTrack(false)
      default: false
      }
      completion(INUpdateMediaAffinityIntentResponse(code: applied ? .success : .failure, userActivity: nil))
    }
  }

  // Siri fails an intent whose handler has no resolution method. They are optional @objc
  // requirements, so each is pinned to the selector SiriKit calls. The handlers act on the
  // playing track, so the spoken item passes through.

  @objc(resolveMediaItemsForAddMedia:withCompletion:)
  func resolveMediaItems(
    for intent: INAddMediaIntent,
    with completion: @escaping ([INAddMediaMediaItemResolutionResult]) -> Void,
  ) {
    completion([.success(with: Self.spokenItem(intent.mediaSearch))])
  }

  /// One flat favorites collection: always the user's library.
  @objc(resolveMediaDestinationForAddMedia:withCompletion:)
  func resolveMediaDestination(
    for _: INAddMediaIntent,
    with completion: @escaping (INAddMediaMediaDestinationResolutionResult) -> Void,
  ) {
    completion(.success(with: INMediaDestination.library))
  }

  @objc(resolveMediaItemsForUpdateMediaAffinity:withCompletion:)
  func resolveMediaItems(
    for intent: INUpdateMediaAffinityIntent,
    with completion: @escaping ([INUpdateMediaAffinityMediaItemResolutionResult]) -> Void,
  ) {
    completion([.success(with: Self.spokenItem(intent.mediaSearch))])
  }

  private static func spokenItem(_ search: INMediaSearch?) -> INMediaItem {
    let name = search?.mediaName ?? ""
    return INMediaItem(identifier: name, title: name, type: .unknown, artwork: nil)
  }

  /// False when nothing is playing, the playing track has no identity to favorite, or the
  /// change throws.
  @MainActor
  private static func favoriteCurrentTrack(_ favorited: Bool) -> Bool {
    guard let browser = HybridAudioBrowser.shared else { return false }
    do {
      return try browser.favoriteActiveTrack(favorited)
    } catch {
      logger.error("favoriteCurrentTrack(\(favorited)) failed: \(error.localizedDescription)")
      return false
    }
  }
}
