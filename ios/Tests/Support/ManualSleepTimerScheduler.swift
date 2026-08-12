@testable import AudioBrowserTestable
import Foundation

/// A job handed back by `ManualSleepTimerScheduler`.
private final class ManualJob: SleepTimerJob {
  nonisolated(unsafe) var isCancelled = false
  func cancel() { isCancelled = true }
}

/// Deterministic stand-in for the main-queue scheduler: jobs fire only when the
/// test advances the clock, so an assertion can never lose a race against a
/// real deadline on a loaded CI runner.
@MainActor
final class ManualSleepTimerScheduler: SleepTimerScheduling {
  private struct Entry {
    let deadline: TimeInterval
    let job: ManualJob
    let work: @MainActor () -> Void
  }

  private var entries: [Entry] = []
  private(set) var now: TimeInterval = 0

  func schedule(
    after delay: TimeInterval,
    _ work: @escaping @Sendable @MainActor () -> Void,
  ) -> any SleepTimerJob {
    let job = ManualJob()
    entries.append(Entry(deadline: now + delay, job: job, work: work))
    return job
  }

  /// Moves the virtual clock to `time`, firing every due job in deadline order.
  /// Jobs run one at a time so a job that schedules or cancels another (the
  /// fade landing before completion) sees the same ordering it would in
  /// production.
  func advance(to time: TimeInterval) {
    now = time
    while let next = entries
      .filter({ !$0.job.isCancelled && $0.deadline <= now })
      .min(by: { $0.deadline < $1.deadline })
    {
      entries.removeAll { $0.job === next.job }
      next.work()
    }
  }
}
