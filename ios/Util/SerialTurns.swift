import os

/// Runs main-actor jobs one at a time, in the order `enqueue` is called, from any thread: each
/// claims its turn before `enqueue` returns, and starts once the job before it has finished.
final class SerialTurns: Sendable {
  private let latest = OSAllocatedUnfairLock<Task<Void, Never>?>(initialState: nil)

  @discardableResult
  func enqueue(_ job: @escaping @MainActor @Sendable () async -> Void) -> Task<Void, Never> {
    latest.withLock { latest in
      let previous = latest
      let next = Task { @MainActor in
        await previous?.value
        await job()
      }
      latest = next
      return next
    }
  }
}
