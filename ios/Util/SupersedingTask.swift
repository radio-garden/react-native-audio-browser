/// At most one task in flight: `run` and `cancel` cancel the one before, and a result is
/// delivered only while its task is the latest, so a cancelled or superseded task delivers nothing.
@MainActor
final class SupersedingTask<Value: Sendable> {
  private var generation: UInt = 0
  private var task: Task<Void, Never>?

  func run(
    _ work: @escaping @MainActor () async throws -> Value,
    deliver: @escaping @MainActor (Result<Value, Error>) -> Void,
  ) {
    cancel()
    let generation = generation
    task = Task { [weak self] in
      let result: Result<Value, Error>
      do {
        result = try await .success(work())
      } catch {
        result = .failure(error)
      }
      guard let self, self.generation == generation else { return }
      self.task = nil
      deliver(result)
    }
  }

  deinit {
    task?.cancel()
  }

  func cancel() {
    generation &+= 1
    task?.cancel()
    task = nil
  }
}
