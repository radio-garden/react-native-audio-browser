@testable import AudioBrowserTestable
import Foundation

private final class ManualJob: SleepTimerJob {
  nonisolated(unsafe) var isCancelled = false
  func cancel() { isCancelled = true }
}

/// Fires jobs only when the test calls `advance(to:)`, so no assertion races a real deadline.
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

  /// Moves the clock to `time` and runs each due job one at a time in deadline order, so a job
  /// that schedules or cancels another sees the same ordering it would in production.
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
