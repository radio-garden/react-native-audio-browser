import Foundation

#if canImport(NitroModules)
  import NitroModules
#endif

typealias SleepTimerState = SleepTimer

protocol SleepTimerJob: AnyObject {
  func cancel()
}

@MainActor
protocol SleepTimerScheduling {
  /// Seconds on the clock `schedule` counts delays on.
  var now: TimeInterval { get }

  func schedule(
    after delay: TimeInterval,
    _ work: @escaping @Sendable @MainActor () -> Void,
  ) -> any SleepTimerJob
}

/// Strict and without leeway: `asyncAfter` lets the system fire up to 5–10% of the delay late,
/// seconds on a timer of minutes.
private final class StrictTimerJob: SleepTimerJob {
  private let source: DispatchSourceTimer

  init(after delay: TimeInterval, _ work: @escaping @Sendable @MainActor () -> Void) {
    source = DispatchSource.makeTimerSource(flags: .strict, queue: .main)
    source.schedule(deadline: .now() + delay, leeway: .nanoseconds(0))
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

/// Holds one sleep timer at a time: a deadline, optionally faded out, or the end of the current
/// track. Setting one replaces the other.
@MainActor
final class SleepTimerManager {
  // MARK: - Properties

  private let scheduler: any SleepTimerScheduling
  private let fader: VolumeFader

  private nonisolated(unsafe) var sleepTimerJob: (any SleepTimerJob)?
  private nonisolated(unsafe) var fadeJob: (any SleepTimerJob)?

  init(fader: VolumeFader, scheduler: any SleepTimerScheduling = MainQueueSleepTimerScheduler()) {
    self.fader = fader
    self.scheduler = scheduler
  }

  var isFading: Bool { fader.isActive }

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

  /// Clears whichever timer is set and cancels any fade, restoring the volume. False, with no
  /// `onChanged`, when none was set.
  @discardableResult
  func clear() -> Bool {
    let hasSleepTimer = sleepTimerTime > -1 || willSleepWhenCurrentItemReachesEnd
    if !hasSleepTimer { return false }
    sleepTimerTime = -1
    willSleepWhenCurrentItemReachesEnd = false
    fader.cancel(restoringVolume: true)
    onChanged?(.first(NullType.null))
    return true
  }

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
  /// seconds, at most `seconds`, so silence lands at the deadline.
  func set(seconds: TimeInterval, fadeDuration: TimeInterval? = nil) {
    cancelSleepTimerJob()
    fader.cancel(restoringVolume: true)
    sleepTimerTime = Date().timeIntervalSince1970 + seconds
    let deadline = scheduler.now + seconds
    sleepTimerJob = scheduler.schedule(after: seconds) { [weak self] in
      self?.complete()
    }
    if let fadeDuration, fadeDuration > 0 {
      let fade = min(fadeDuration, seconds)
      fadeJob = scheduler.schedule(after: seconds - fade) { [weak self] in
        self?.fader.start(endingAt: deadline, duration: fade)
      }
    }
    if let state = get() { onChanged?(state) }
  }

  func setToEndOfTrack() {
    let changed = !willSleepWhenCurrentItemReachesEnd
    willSleepWhenCurrentItemReachesEnd = true
    if changed {
      fader.cancel(restoringVolume: true)
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
  }

  private func complete() {
    sleepTimerTime = -1
    willSleepWhenCurrentItemReachesEnd = false
    onChanged?(.first(NullType.null))
    fader.resolve { onComplete?() }
  }

  deinit {
    sleepTimerJob?.cancel()
    fadeJob?.cancel()
  }
}
