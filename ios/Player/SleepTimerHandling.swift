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

  // Consumer-facing surface — HybridAudioBrowser drives these through
  // TrackPlayer, so they belong here rather than behind a concrete downcast.

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
