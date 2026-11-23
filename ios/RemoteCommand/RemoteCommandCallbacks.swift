#if canImport(NitroModules)
  import NitroModules
#endif

/// What `RemoteCommandController` calls when the system delivers a remote command (lock
/// screen, Control Center, CarPlay, headset), and the one state it reads to route a toggle.
/// `AVPlayerEngineCallbacks` extends it, so the controller depends only on these.
@MainActor protocol RemoteCommandCallbacks: AnyObject {
  /// `Player.remoteIsPlaying`: decides which way `remoteTogglePlayPause()` goes.
  var remoteIsPlaying: Bool { get }

  func remotePlay()

  func remotePause()

  func remoteStop()

  func remoteNext()

  func remotePrevious()

  /// `interval` in seconds.
  func remoteJumpForward(interval: Double)

  /// `interval` in seconds.
  func remoteJumpBackward(interval: Double)

  /// `position` in seconds.
  func remoteSeek(position: Double)

  func remoteChangeRepeatMode(mode: RepeatMode)

  func remoteChangeShuffleMode(enabled: Bool)

  func remoteChangePlaybackRate(rate: Float)
}

extension RemoteCommandCallbacks {
  /// A headset click or a one-button remote: pauses when playing, plays otherwise, through
  /// `remotePause()` and `remotePlay()`, so it reaches JS as those buttons do. After a terminal
  /// error the play intent is still raised but the player is not playing, so a click reloads.
  func remoteTogglePlayPause() {
    if remoteIsPlaying { remotePause() } else { remotePlay() }
  }
}
