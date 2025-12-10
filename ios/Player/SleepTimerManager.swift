import Foundation
import NitroModules

/// Type alias for the Nitro SleepTimer variant type
typealias SleepTimerState = SleepTimer

/// Manages sleep timer functionality for the audio player.
/// Supports both time-based timers and end-of-track timers.
class SleepTimerManager {
  // MARK: - Properties

  private var sleepTimerJob: DispatchWorkItem?

  private func assertMainThread() {
    assert(Thread.isMainThread, "SleepTimerManager must be accessed from the main thread")
  }

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

  /// Callback invoked when the sleep timer state changes
  var onChanged: ((SleepTimerState?) -> Void)?

  // MARK: - Public Methods

  /// Clears any active sleep timer.
  /// - Returns: true if a timer was cleared, false if no timer was active
  @discardableResult
  func clear() -> Bool {
    assertMainThread()
    let hasSleepTimer = sleepTimerTime > -1 || willSleepWhenCurrentItemReachesEnd
    if !hasSleepTimer { return false }
    sleepTimerTime = -1
    willSleepWhenCurrentItemReachesEnd = false
    onChanged?(nil)
    return true
  }

  /// Gets the current sleep timer state.
  /// - Returns: The current timer state, or nil if no timer is active
  func get() -> SleepTimerState? {
    assertMainThread()
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
  /// - Parameter seconds: Number of seconds until playback stops
  func set(seconds: TimeInterval) {
    assertMainThread()
    cancelSleepTimerJob()
    let job = DispatchWorkItem { [weak self] in
      self?.complete()
    }
    sleepTimerJob = job
    sleepTimerTime = Date().timeIntervalSince1970 + seconds
    DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: job)
    onChanged?(get())
  }

  /// Sets the timer to stop playback when the current track ends.
  func setToEndOfTrack() {
    assertMainThread()
    let changed = !willSleepWhenCurrentItemReachesEnd
    willSleepWhenCurrentItemReachesEnd = true
    if changed {
      onChanged?(get())
    }
  }

  /// Called when the current track changes. Resets end-of-track timer.
  func onTrackChanged() {
    if willSleepWhenCurrentItemReachesEnd {
      willSleepWhenCurrentItemReachesEnd = false
      onChanged?(nil)
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
  }

  private func complete() {
    sleepTimerTime = -1
    willSleepWhenCurrentItemReachesEnd = false
    onChanged?(nil)
    onComplete?()
  }

  deinit {
    cancelSleepTimerJob()
  }
}
