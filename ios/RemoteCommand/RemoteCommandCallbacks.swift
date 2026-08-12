#if canImport(NitroModules)
  import NitroModules
#endif

/// The callbacks `RemoteCommandController` invokes when the system delivers a
/// remote command (lock screen, Control Center, CarPlay, headset).
///
/// Split out of `TrackPlayerCallbacks` — which the controller does not otherwise
/// use — so the controller depends only on what it calls. `TrackPlayerCallbacks`
/// extends this protocol, so conformers implement both at once.
@MainActor protocol RemoteCommandCallbacks: AnyObject {
  /// Called when play is triggered remotely.
  func remotePlay()

  /// Called when pause is triggered remotely.
  func remotePause()

  /// Called when stop is triggered remotely.
  func remoteStop()

  /// Called when toggle play/pause is triggered remotely.
  func remotePlayPause()

  /// Called when next is triggered remotely.
  func remoteNext()

  /// Called when previous is triggered remotely.
  func remotePrevious()

  /// Called when jump forward is triggered remotely.
  func remoteJumpForward(interval: Double)

  /// Called when jump backward is triggered remotely.
  func remoteJumpBackward(interval: Double)

  /// Called when seek is triggered remotely.
  func remoteSeek(position: Double)

  /// Called when repeat mode change is triggered remotely (CarPlay/lock screen).
  func remoteChangeRepeatMode(mode: RepeatMode)

  /// Called when shuffle mode change is triggered remotely (CarPlay/lock screen).
  func remoteChangeShuffleMode(enabled: Bool)

  /// Called when playback rate change is triggered remotely (CarPlay/lock screen).
  func remoteChangePlaybackRate(rate: Float)
}
