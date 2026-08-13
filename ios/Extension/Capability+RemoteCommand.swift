import Foundation
import NitroModules

/// Extension to build RemoteCommands from PlayerCapabilities
extension PlayerCapabilities {
  /// Build array of enabled RemoteCommands based on capabilities
  /// All capabilities are enabled by default - only false values disable them
  func buildRemoteCommands(
    forwardJumpInterval: NSNumber?,
    backwardJumpInterval: NSNumber?,
    playbackRates: [Double],
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
        preferredIntervals: [(forwardJumpInterval ?? backwardJumpInterval) ?? 15],
      ))
    }
    if jumpBackward != false {
      commands.append(.skipBackward(
        preferredIntervals: [(backwardJumpInterval ?? forwardJumpInterval) ?? 15],
      ))
    }

    // NOTE: We deliberately do NOT register MPRemoteCommandCenter.likeCommand for the
    // `favorite` capability. That MPFeedbackCommand had a single system surface — a
    // pre-iOS-15 lock-screen menu shown in place of the previous-track button — which
    // Apple silently removed. On the iOS 16+ target it can never be invoked: CarPlay
    // uses its own CPNowPlayingButton heart, and Siri "I like this" routes through
    // INUpdateMediaAffinityIntent. The `favorite` capability still drives the
    // favorite row indicators via setFavoriteEnabled. See issues #67 / #71.

    // Mode controls
    if shuffleMode != false {
      commands.append(.changeShuffleMode)
    }
    if repeatMode != false {
      commands.append(.changeRepeatMode)
    }
    if playbackRate != false {
      commands.append(.changePlaybackRate(
        supportedPlaybackRates: playbackRates.map { NSNumber(value: $0) },
      ))
    }

    return commands
  }
}
