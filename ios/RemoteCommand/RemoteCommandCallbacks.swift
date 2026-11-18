#if canImport(NitroModules)
  import NitroModules
#endif

@MainActor protocol RemoteCommandCallbacks: AnyObject {
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
