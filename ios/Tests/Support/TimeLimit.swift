import Foundation
import Testing

struct TimeLimitExceeded: Error {}

/// `body`'s result, or a recorded issue and `TimeLimitExceeded` once `limit` elapses. Polls an
/// unstructured task rather than racing a task group, so a body that ignores cancellation fails
/// the test instead of hanging it.
func withinTimeLimit<T: Sendable>(
  _ limit: Duration = .seconds(5),
  sourceLocation: SourceLocation = #_sourceLocation,
  _ body: @escaping @Sendable () async throws -> T,
) async throws -> T {
  let task = Task { try await body() }
  let finished = Flag()
  Task {
    _ = await task.result
    finished.set()
  }
  let deadline = ContinuousClock.now + limit
  while !finished.isSet {
    if ContinuousClock.now >= deadline {
      task.cancel()
      Issue.record("still running after \(limit)", sourceLocation: sourceLocation)
      throw TimeLimitExceeded()
    }
    try await Task.sleep(for: .milliseconds(5))
  }
  return try await task.value
}

final class Flag: @unchecked Sendable {
  private let lock = NSLock()
  private var value = false

  var isSet: Bool {
    lock.withLock { value }
  }

  func set() {
    lock.withLock { value = true }
  }
}
