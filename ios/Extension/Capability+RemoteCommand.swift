import Foundation

#if canImport(NitroModules)
  import NitroModules
#endif

extension PlayerCapabilities {
  /// The remote commands to enable: each capability's command unless it is set to false, so an
  /// unset one counts as enabled. `favorite` enables none: no iOS 16+ surface invokes
  /// `likeCommand`; CarPlay has its own heart button and Siri its affinity intent.
  var remoteCommands: Set<RemoteCommand> {
    let enabled: [(Bool?, RemoteCommand)] = [
      (play, .play),
      (pause, .pause),
      (stop, .stop),
      (skipToNext, .next),
      (skipToPrevious, .previous),
      (seekTo, .changePlaybackPosition),
      (jumpForward, .skipForward),
      (jumpBackward, .skipBackward),
      (shuffleMode, .changeShuffleMode),
      (repeatMode, .changeRepeatMode),
      (playbackRate, .changePlaybackRate),
    ]
    return Set(enabled.compactMap { $0.0 == false ? nil : $0.1 })
  }
}
