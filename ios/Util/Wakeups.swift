import Foundation

/// Waits that end on a posted event, each judging every event for itself.
@MainActor
final class Wakeups<Event> {
  enum Verdict {
    case keepWaiting
    case resume
    /// The wait is no longer wanted: it throws `GaveUp`.
    case giveUp
  }

  struct GaveUp: Error {}

  private struct Waiter {
    let judge: (Event) -> Verdict
    let continuation: CheckedContinuation<Void, any Error>
  }

  private var waiters: [UUID: Waiter] = [:]
  private var ended = false

  var waiterCount: Int { waiters.count }

  /// Suspends until an event `judge` resumes on. Throws `GaveUp` when it gives up, and
  /// `CancellationError` when the task is cancelled or `end()` runs.
  func wait(judge: @escaping (Event) -> Verdict) async throws {
    let id = UUID()
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
        if Task.isCancelled || ended {
          continuation.resume(throwing: CancellationError())
        } else {
          waiters[id] = Waiter(judge: judge, continuation: continuation)
        }
      }
    } onCancel: { [weak self] in
      Task { @MainActor in self?.finish(id, throwing: CancellationError()) }
    }
  }

  func post(_ event: Event) {
    for (id, waiter) in waiters where waiters[id] != nil {
      switch waiter.judge(event) {
      case .keepWaiting:
        break
      case .resume:
        waiters.removeValue(forKey: id)?.continuation.resume()
      case .giveUp:
        finish(id, throwing: GaveUp())
      }
    }
  }

  /// Ends every wait, and any wait after, with a `CancellationError`.
  func end() {
    ended = true
    for id in waiters.keys {
      finish(id, throwing: CancellationError())
    }
  }

  private func finish(_ id: UUID, throwing error: any Error) {
    waiters.removeValue(forKey: id)?.continuation.resume(throwing: error)
  }
}

extension Wakeups.Verdict {
  /// Gives up once `row` is gone, else resumes once `ready`.
  static func waiting(for row: AnyObject?, ready: Bool) -> Self {
    guard row != nil else { return .giveUp }
    return ready ? .resume : .keepWaiting
  }
}
