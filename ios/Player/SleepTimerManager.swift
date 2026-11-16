import Foundation

#if canImport(NitroModules)
  import NitroModules
#endif

typealias SleepTimerState = SleepTimer

/// A scheduled job not yet run. `cancel()` is nonisolated so `deinit`, always nonisolated in
/// Swift 6, can tear pending work down.
protocol SleepTimerJob: AnyObject {
  func cancel()
}

/// Runs work after a delay; injected so tests advance the clock by hand instead of racing it.
@MainActor
protocol SleepTimerScheduling {
  /// Seconds on the clock `schedule` counts delays on.
  var now: TimeInterval { get }

  func schedule(
    after delay: TimeInterval,
    _ work: @escaping @Sendable @MainActor () -> Void,
  ) -> any SleepTimerJob
}

/// Fires on the main queue at the deadline. Strict and without leeway: `asyncAfter` lets the
/// system fire up to 5–10% of the delay late, seconds on a timer of minutes.
private final class StrictTimerJob: SleepTimerJob {
  private let source: DispatchSourceTimer

  init(after delay: TimeInterval, _ work: @escaping @Sendable @MainActor () -> Void) {
    source = DispatchSource.makeTimerSource(flags: .strict, queue: .main)
    source.schedule(deadline: .now() + delay, leeway: .nanoseconds(0))
    // The handler holds the source until it fires or is cancelled.
    source.setEventHandler { [source] in
      source.cancel()
      MainActor.assumeIsolated { work() }
    }
    source.resume()
  }

  func cancel() {
    source.cancel()
  }
}

struct MainQueueSleepTimerScheduler: SleepTimerScheduling {
  var now: TimeInterval {
    TimeInterval(DispatchTime.now().uptimeNanoseconds) / 1_000_000_000
  }

  func schedule(
    after delay: TimeInterval,
    _ work: @escaping @Sendable @MainActor () -> Void,
  ) -> any SleepTimerJob {
    StrictTimerJob(after: delay, work)
  }
}

/// A fade placed on the current item's timeline, so the listener hears it at those positions
/// however far ahead the route takes the audio: AirPlay 2 sends seconds ahead of what plays, and
/// a fade of the player's volume lands in audio a pause then drops.
@MainActor
protocol TimelineFading: AnyObject {
  /// Fades the item's audio to silence over `duration` seconds of its timeline, ending
  /// `secondsAhead` after the position heard now. False when the route or OS can't; the caller
  /// then fades the volume.
  func startTimelineFade(endingIn secondsAhead: TimeInterval, duration: TimeInterval) -> Bool
  /// Removes the fade, restoring the item's audio.
  func cancelTimelineFade()
}

/// Holds one sleep timer at a time: a deadline, optionally faded out, or the end of the current
/// track. Setting one replaces the other.
@MainActor
final class SleepTimerManager {
  // MARK: - Properties

  private let scheduler: any SleepTimerScheduling
  private let fader: VolumeFader

  // nonisolated(unsafe) so deinit, always nonisolated in Swift 6, can cancel them.
  private nonisolated(unsafe) var sleepTimerJob: (any SleepTimerJob)?
  private nonisolated(unsafe) var fadeJob: (any SleepTimerJob)?
  private nonisolated(unsafe) var timelineFadeJob: (any SleepTimerJob)?

  private weak var timelineFader: (any TimelineFading)?
  private var timelineFadePlaced = false
  private var timelineFadeRunning = false

  /// Seconds before the fade window the timeline fade is placed: AirPlay 2 renders this far ahead
  /// at most, so the fade is in place before the audio it covers.
  static let timelineFadeLeadSeconds: TimeInterval = 60

  init(
    fader: VolumeFader,
    timelineFader: (any TimelineFading)? = nil,
    scheduler: any SleepTimerScheduling = MainQueueSleepTimerScheduler(),
  ) {
    self.fader = fader
    self.timelineFader = timelineFader
    self.scheduler = scheduler
  }

  var isFading: Bool { fader.isActive || timelineFadeRunning }

  /// The deadline in seconds since 1970, or -1 when there is none.
  private(set) var sleepTimerTime: TimeInterval = -1 {
    didSet {
      if sleepTimerTime > -1 {
        willSleepWhenCurrentItemReachesEnd = false
      } else {
        cancelSleepTimerJob()
      }
    }
  }

  private(set) var willSleepWhenCurrentItemReachesEnd: Bool = false {
    didSet {
      if willSleepWhenCurrentItemReachesEnd {
        sleepTimerTime = -1
      }
    }
  }

  /// Halts playback when the timer fires; the pre-fade volume is restored after it returns.
  var onComplete: (() -> Void)?

  var onChanged: ((SleepTimerState) -> Void)?

  // MARK: - Public Methods

  /// Clears whichever timer is set and cancels any fade, restoring the volume. False, with no `onChanged`, when none
  /// was set.
  @discardableResult
  func clear() -> Bool {
    let hasSleepTimer = sleepTimerTime > -1 || willSleepWhenCurrentItemReachesEnd
    if !hasSleepTimer { return false }
    sleepTimerTime = -1
    willSleepWhenCurrentItemReachesEnd = false
    cancelFades()
    onChanged?(.first(NullType.null))
    return true
  }

  /// The active timer, its deadline in milliseconds since 1970; nil when none is set.
  func get() -> SleepTimerState? {
    let sleepOnEnd = willSleepWhenCurrentItemReachesEnd
    let hasSleepTimerTime = sleepTimerTime > -1

    if !hasSleepTimerTime, !sleepOnEnd { return nil }

    if hasSleepTimerTime {
      return .second(SleepTimerTime(time: sleepTimerTime * 1000))
    }

    if sleepOnEnd {
      return .third(SleepTimerEndOfTrack(sleepWhenPlayedToEnd: true))
    }

    return nil
  }

  /// Stops playback `seconds` from now. With `fadeDuration`, the volume fades over that many
  /// seconds (clamped to `seconds`) so silence lands at the deadline, also when the fade starts
  /// late.
  func set(seconds: TimeInterval, fadeDuration: TimeInterval? = nil) {
    cancelSleepTimerJob()
    cancelFades()
    sleepTimerTime = Date().timeIntervalSince1970 + seconds
    let deadline = scheduler.now + seconds
    sleepTimerJob = scheduler.schedule(after: seconds) { [weak self] in
      self?.complete()
    }
    if let fadeDuration, fadeDuration > 0 {
      let fade = min(fadeDuration, seconds)
      timelineFadeJob = scheduler.schedule(
        after: max(0, seconds - fade - Self.timelineFadeLeadSeconds),
      ) { [weak self] in
        guard let self, let timelineFader else { return }
        timelineFadePlaced = timelineFader.startTimelineFade(
          endingIn: deadline - scheduler.now,
          duration: fade,
        )
      }
      fadeJob = scheduler.schedule(after: seconds - fade) { [weak self] in
        guard let self else { return }
        if timelineFadePlaced {
          timelineFadeRunning = true
        } else {
          fader.start(endingAt: deadline, duration: fade)
        }
      }
    }
    if let state = get() { onChanged?(state) }
  }

  func setToEndOfTrack() {
    let changed = !willSleepWhenCurrentItemReachesEnd
    willSleepWhenCurrentItemReachesEnd = true
    if changed {
      cancelFades()
      if let state = get() { onChanged?(state) }
    }
  }

  /// Clears an end-of-track timer: it applies to the track it was set on, not the next.
  func onTrackChanged() {
    if willSleepWhenCurrentItemReachesEnd {
      willSleepWhenCurrentItemReachesEnd = false
      onChanged?(.first(NullType.null))
    }
  }

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
    timelineFadeJob?.cancel()
    timelineFadeJob = nil
  }

  private func cancelFades() {
    fader.cancel(restoringVolume: true)
    endTimelineFade()
  }

  private func endTimelineFade() {
    if timelineFadePlaced { timelineFader?.cancelTimelineFade() }
    timelineFadePlaced = false
    timelineFadeRunning = false
  }

  private func complete() {
    sleepTimerTime = -1
    willSleepWhenCurrentItemReachesEnd = false
    onChanged?(.first(NullType.null))
    fader.resolve { onComplete?() }
    // After the halt, so the restored audio is never heard.
    endTimelineFade()
  }

  deinit {
    sleepTimerJob?.cancel()
    fadeJob?.cancel()
    timelineFadeJob?.cancel()
  }
}
