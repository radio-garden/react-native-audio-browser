import Foundation

#if canImport(NitroModules)
  import NitroModules
#endif

/// Type alias for the Nitro SleepTimer variant type
typealias SleepTimerState = SleepTimer

/// Manages sleep timer functionality for the audio player.
/// Supports both time-based timers and end-of-track timers.
@MainActor
class SleepTimerManager: SleepTimerHandling {
  // MARK: - Properties

  // nonisolated(unsafe) for deinit cleanup — deinit is always nonisolated in Swift 6.
  private nonisolated(unsafe) var sleepTimerJob: DispatchWorkItem?
  private nonisolated(unsafe) var fadeJob: DispatchWorkItem?

  /// The time when playback should stop (seconds since epoch), or -1 if inactive
  private(set) var sleepTimerTime: TimeInterval = -1 {
    didSet {
      if sleepTimerTime > -1 {
        willSleepWhenCurrentItemReachesEnd = false
      } else {
        cancelSleepTimerJob()
      }
    }
  }

  /// Whether to stop playback when the current track ends
  private(set) var willSleepWhenCurrentItemReachesEnd: Bool = false {
    didSet {
      if willSleepWhenCurrentItemReachesEnd {
        sleepTimerTime = -1
      }
    }
  }

  /// Callback invoked when the sleep timer fires
  var onComplete: (() -> Void)?

  /// Callback invoked when the fade-out window of a time-based timer begins
  var onFadeStart: ((_ duration: TimeInterval) -> Void)?

  /// Callback invoked when a timer is cancelled/replaced while its fade may be
  /// running — the listener restores the pre-fade volume. Not invoked on
  /// completion: there the pause lands first, then the volume is restored.
  var onFadeCancel: (() -> Void)?

  /// Callback invoked when the sleep timer state changes
  var onChanged: ((SleepTimerState) -> Void)?

  // MARK: - Public Methods

  /// Clears any active sleep timer.
  /// - Returns: true if a timer was cleared, false if no timer was active
  @discardableResult
  func clear() -> Bool {
    let hasSleepTimer = sleepTimerTime > -1 || willSleepWhenCurrentItemReachesEnd
    if !hasSleepTimer { return false }
    sleepTimerTime = -1
    willSleepWhenCurrentItemReachesEnd = false
    onFadeCancel?()
    onChanged?(.first(NullType.null))
    return true
  }

  /// Gets the current sleep timer state.
  /// - Returns: The current timer state, or nil if no timer is active
  func get() -> SleepTimerState? {
    let sleepOnEnd = willSleepWhenCurrentItemReachesEnd
    let hasSleepTimerTime = sleepTimerTime > -1

    if !hasSleepTimerTime, !sleepOnEnd { return nil }

    if hasSleepTimerTime {
      // Return time in milliseconds since epoch
      return .second(SleepTimerTime(time: sleepTimerTime * 1000))
    }

    if sleepOnEnd {
      return .third(SleepTimerEndOfTrack(sleepWhenPlayedToEnd: true))
    }

    return nil
  }

  /// Sets a time-based sleep timer.
  /// - Parameters:
  ///   - seconds: Number of seconds until playback stops
  ///   - fadeDuration: Seconds over which to fade the volume out before the
  ///     deadline (clamped to `seconds`); silence lands exactly at the deadline
  func set(seconds: TimeInterval, fadeDuration: TimeInterval? = nil) {
    cancelSleepTimerJob()
    onFadeCancel?()
    let job = DispatchWorkItem { [weak self] in
      MainActor.assumeIsolated {
        self?.complete()
      }
    }
    sleepTimerJob = job
    sleepTimerTime = Date().timeIntervalSince1970 + seconds
    DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: job)
    if let fadeDuration, fadeDuration > 0 {
      let fade = min(fadeDuration, seconds)
      let fadeJob = DispatchWorkItem { [weak self] in
        MainActor.assumeIsolated {
          self?.onFadeStart?(fade)
        }
      }
      self.fadeJob = fadeJob
      DispatchQueue.main.asyncAfter(deadline: .now() + (seconds - fade), execute: fadeJob)
    }
    if let state = get() { onChanged?(state) }
  }

  /// Sets the timer to stop playback when the current track ends.
  func setToEndOfTrack() {
    let changed = !willSleepWhenCurrentItemReachesEnd
    willSleepWhenCurrentItemReachesEnd = true
    if changed {
      onFadeCancel?()
      if let state = get() { onChanged?(state) }
    }
  }

  /// Called when the current track changes. Resets end-of-track timer.
  func onTrackChanged() {
    if willSleepWhenCurrentItemReachesEnd {
      willSleepWhenCurrentItemReachesEnd = false
      onChanged?(.first(NullType.null))
    }
  }

  /// Called when the current track plays to end.
  /// Triggers completion if end-of-track timer is active.
  func onTrackPlayedToEnd() {
    if willSleepWhenCurrentItemReachesEnd {
      complete()
    }
  }

  // MARK: - Private Methods

  private func cancelSleepTimerJob() {
    sleepTimerJob?.cancel()
    sleepTimerJob = nil
    fadeJob?.cancel()
    fadeJob = nil
  }

  private func complete() {
    sleepTimerTime = -1
    willSleepWhenCurrentItemReachesEnd = false
    onChanged?(.first(NullType.null))
    onComplete?()
  }

  deinit {
    sleepTimerJob?.cancel()
    fadeJob?.cancel()
  }
}
