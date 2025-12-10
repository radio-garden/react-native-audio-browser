import Foundation
import NitroModules

/// Extension to map Nitro Capability to RemoteCommand for MPRemoteCommandCenter
extension Capability {
  func mapToPlayerCommand(
    forwardJumpInterval: NSNumber?,
    backwardJumpInterval: NSNumber?,
    likeOptions: FeedbackOptions,
    dislikeOptions _: FeedbackOptions,
    bookmarkOptions: FeedbackOptions
  ) -> RemoteCommand {
    switch self {
    case .stop:
      .stop
    case .play:
      .play
    case .pause:
      .pause
    case .skipToNext:
      .next
    case .skipToPrevious:
      .previous
    case .seekTo:
      .changePlaybackPosition
    case .jumpForward:
      .skipForward(preferredIntervals: [(forwardJumpInterval ?? backwardJumpInterval) ?? 15])
    case .jumpBackward:
      .skipBackward(preferredIntervals: [
        (backwardJumpInterval ?? forwardJumpInterval) ?? 15,
      ])
    case .favorite:
      // Map favorite to like command
      .like(
        isActive: likeOptions.isActive,
        localizedTitle: likeOptions.title,
        localizedShortTitle: likeOptions.title
      )
    case .bookmark:
      .bookmark(
        isActive: bookmarkOptions.isActive,
        localizedTitle: bookmarkOptions.title,
        localizedShortTitle: bookmarkOptions.title
      )
    case .playFromId, .playFromSearch, .skip:
      // These don't have direct RemoteCommand mappings
      // Return play as a fallback
      .play
    }
  }
}
