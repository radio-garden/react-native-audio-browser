import Testing

@testable import AudioBrowserTestable

@Suite("PlayerCapabilities.remoteCommands")
struct CapabilityRemoteCommandTests {
  @Test func everyCommandIsEnabledUnlessSetToFalse() {
    #expect(PlayerCapabilities().remoteCommands == Set(RemoteCommand.allCases))
  }

  @Test func eachCapabilitySetToFalseDropsExactlyItsCommand() {
    let pairs: [(WritableKeyPath<PlayerCapabilities, Bool?>, RemoteCommand)] = [
      (\.play, .play),
      (\.pause, .pause),
      (\.stop, .stop),
      (\.skipToNext, .next),
      (\.skipToPrevious, .previous),
      (\.seekTo, .changePlaybackPosition),
      (\.jumpForward, .skipForward),
      (\.jumpBackward, .skipBackward),
      (\.shuffleMode, .changeShuffleMode),
      (\.repeatMode, .changeRepeatMode),
      (\.playbackRate, .changePlaybackRate),
    ]
    for (capability, command) in pairs {
      var capabilities = PlayerCapabilities()
      capabilities[keyPath: capability] = false
      #expect(capabilities.remoteCommands == Set(RemoteCommand.allCases).subtracting([command]), "\(command)")
    }
  }

  @Test func togglePlayPauseGoesOnlyWhenPlayAndPauseAreBothFalse() {
    #expect(PlayerCapabilities(play: false).remoteCommands.contains(.togglePlayPause))
    #expect(PlayerCapabilities(pause: false).remoteCommands.contains(.togglePlayPause))
    #expect(
      PlayerCapabilities(play: false, pause: false).remoteCommands
        == Set(RemoteCommand.allCases).subtracting([.play, .pause, .togglePlayPause]),
    )
  }

  @Test func favoriteEnablesNoCommand() {
    #expect(PlayerCapabilities(favorite: false).remoteCommands == Set(RemoteCommand.allCases))
  }
}
