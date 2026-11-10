/// A value computed at most once per generation and shared by every caller of that generation.
///
/// `invalidate()` starts a new generation. A caller whose computation finishes or fails after
/// an invalidation computes again, so `value(_:)` never returns a stale result; `compute` must
/// therefore read the state it depends on when it runs, not capture it. A failure is not kept:
/// the next caller of the same generation tries again. A cancelled caller leaves at once and stops
/// retrying; the computation runs on for the others.
@MainActor
final class GenerationMemo<Value: Sendable> {
  private var generation = 0
  private var current: (generation: Int, task: Task<Value, Error>)?

  func invalidate() {
    generation += 1
  }

  func value(_ compute: @escaping @MainActor () async throws -> Value) async throws -> Value {
    while true {
      try Task.checkCancellation()
      let generation = generation
      let task: Task<Value, Error>
      if let current, current.generation == generation {
        task = current.task
      } else {
        task = Task {
          do {
            return try await compute()
          } catch {
            if self.current?.generation == generation { self.current = nil }
            throw error
          }
        }
        current = (generation, task)
      }
      do {
        let value = try await awaitCallback { deliver in Task { await deliver(task.result) } }
        if generation == self.generation { return value }
      } catch {
        if generation == self.generation { throw error }
      }
    }
  }
}
