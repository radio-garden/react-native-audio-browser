import Foundation

/// Protocol abstracting sleep timer for testability.
/// PlaybackCoordinator uses this instead of SleepTimerManager directly.
@MainActor protocol SleepTimerHandling: AnyObject {
  var onComplete: (() -> Void)? { get set }
  /// Invoked when the fade-out window of a time-based timer begins.
  var onFadeStart: ((_ duration: TimeInterval) -> Void)? { get set }
  /// Invoked when a timer is cancelled/replaced while its fade may be running —
  /// the listener restores the pre-fade volume.
  var onFadeCancel: (() -> Void)? { get set }
  @discardableResult func clear() -> Bool
  func onTrackChanged()
  func onTrackPlayedToEnd()

  // The consumer-facing surface. Previously absent, which forced
  // `TrackPlayer.sleepTimerManager` to re-widen the coordinator's existential
  // with `as! SleepTimerManager` — defeating the seam, since a fake injected
  // into the coordinator could never reach a caller going through TrackPlayer.

  /// Invoked when the timer state changes (set, cleared, completed).
  var onChanged: ((SleepTimerState) -> Void)? { get set }
  /// The current timer state, or nil when no timer is active.
  func get() -> SleepTimerState?
  /// Sets a time-based timer, optionally fading out over the last
  /// `fadeDuration` seconds.
  func set(seconds: TimeInterval, fadeDuration: TimeInterval?)
  /// Sets the timer to stop playback when the current track ends.
  func setToEndOfTrack()
}
