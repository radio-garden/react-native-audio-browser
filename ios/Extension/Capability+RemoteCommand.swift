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

    // TODO: Investigate where localizedTitle/localizedShortTitle are displayed
    // (possibly only for accessibility/VoiceOver). See TODO.md for details.
    if favorite != false {
      commands.append(.like(
        isActive: false,
        localizedTitle: "Favorite",
        localizedShortTitle: "Favorite",
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
        supportedPlaybackRates: playbackRates.map { NSNumber(value: $0) },
      ))
    }

    return commands
  }
}
