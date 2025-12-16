import Foundation
import NitroModules

/// Extension to build RemoteCommands from PlayerCapabilities
extension PlayerCapabilities {
  /// Build array of enabled RemoteCommands based on capabilities
  /// All capabilities are enabled by default - only false values disable them
  func buildRemoteCommands(
    forwardJumpInterval: NSNumber?,
    backwardJumpInterval: NSNumber?,
    likeOptions: FeedbackOptions,
    dislikeOptions _: FeedbackOptions,
    bookmarkOptions: FeedbackOptions,
    playbackRates: [Double]
  ) -> [RemoteCommand] {
    var commands: [RemoteCommand] = []

    // Play/Pause/Stop
    if play != false {
      commands.append(.play)
    }
    if pause != false {
      commands.append(.pause)
    }
    if stop != false {
      commands.append(.stop)
    }

    // Navigation
    if skipToNext != false {
      commands.append(.next)
    }
    if skipToPrevious != false {
      commands.append(.previous)
    }

    // Seeking
    if seekTo != false {
      commands.append(.changePlaybackPosition)
    }

    // Jump forward/backward
    if jumpForward != false {
      commands.append(.skipForward(
        preferredIntervals: [(forwardJumpInterval ?? backwardJumpInterval) ?? 15]
      ))
    }
    if jumpBackward != false {
      commands.append(.skipBackward(
        preferredIntervals: [(backwardJumpInterval ?? forwardJumpInterval) ?? 15]
      ))
    }

    // Feedback buttons
    if favorite != false {
      commands.append(.like(
        isActive: likeOptions.isActive,
        localizedTitle: likeOptions.title,
        localizedShortTitle: likeOptions.title
      ))
    }
    if bookmark != false {
      commands.append(.bookmark(
        isActive: bookmarkOptions.isActive,
        localizedTitle: bookmarkOptions.title,
        localizedShortTitle: bookmarkOptions.title
      ))
    }

    // Mode controls
    if shuffleMode != false {
      commands.append(.changeShuffleMode)
    }
    if repeatMode != false {
      commands.append(.changeRepeatMode)
    }
    if playbackRate != false {
      commands.append(.changePlaybackRate(
        supportedPlaybackRates: playbackRates.map { NSNumber(value: $0) }
      ))
    }

    return commands
  }
}
