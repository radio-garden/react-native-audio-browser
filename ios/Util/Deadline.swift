/// `work`'s result, or nil once `timeout` passes, when `work` is cancelled first: the answer comes on
/// time even when `work` ignores cancellation, and work that resumes after a nil answer sees
/// `Task.isCancelled`. `work` gets the deadline, to bound its own waits by what is left. Cancelling
/// the caller does not reach `work`.
@MainActor
func withDeadline<T: Sendable>(
  _ timeout: Duration,
  _ work: @escaping @MainActor (_ deadline: ContinuousClock.Instant) async -> T,
) async -> T? {
  let deadline = ContinuousClock.now + timeout
  return await withCheckedContinuation { continuation in
    let answer = DeadlineAnswer(continuation)
    let task = Task { await answer.settle(work(deadline)) }
    answer.timer = Task {
      try? await Task.sleep(until: deadline, clock: .continuous)
      guard !Task.isCancelled else { return }
      task.cancel()
      answer.settle(nil)
    }
  }
}

/// Settles on the main actor, so the work's last step and the timer cannot interleave.
@MainActor
private final class DeadlineAnswer<T: Sendable> {
  private var continuation: CheckedContinuation<T?, Never>?
  var timer: Task<Void, Never>?

  init(_ continuation: CheckedContinuation<T?, Never>) {
    self.continuation = continuation
  }

  func settle(_ value: T?) {
    timer?.cancel()
    continuation?.resume(returning: value)
    continuation = nil
  }
}
