import Foundation
import NitroModules

/// Extension to map Nitro Capability to RemoteCommand for MPRemoteCommandCenter
extension Capability {
  func mapToPlayerCommand(
    forwardJumpInterval: NSNumber?,
    backwardJumpInterval: NSNumber?,
    likeOptions: FeedbackOptions,
    dislikeOptions: FeedbackOptions,
    bookmarkOptions: FeedbackOptions
  ) -> RemoteCommand {
    switch self {
    case .stop:
      return .stop
    case .play:
      return .play
    case .pause:
      return .pause
    case .skipToNext:
      return .next
    case .skipToPrevious:
      return .previous
    case .seekTo:
      return .changePlaybackPosition
    case .jumpForward:
      return .skipForward(preferredIntervals: [(forwardJumpInterval ?? backwardJumpInterval) ?? 15])
    case .jumpBackward:
      return .skipBackward(preferredIntervals: [
        (backwardJumpInterval ?? forwardJumpInterval) ?? 15,
      ])
    case .favorite:
      // Map favorite to like command
      return .like(
        isActive: likeOptions.isActive,
        localizedTitle: likeOptions.title,
        localizedShortTitle: likeOptions.title
      )
    case .bookmark:
      return .bookmark(
        isActive: bookmarkOptions.isActive,
        localizedTitle: bookmarkOptions.title,
        localizedShortTitle: bookmarkOptions.title
      )
    case .playFromId, .playFromSearch, .skip:
      // These don't have direct RemoteCommand mappings
      // Return play as a fallback
      return .play
    }
  }
}
