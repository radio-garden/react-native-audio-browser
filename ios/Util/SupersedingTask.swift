/// At most one task in flight: `run` and `cancel` cancel the one before, and a result is
/// delivered only while its task is the latest, so a cancelled or superseded task delivers nothing.
/// A `run` with the latest run's non-nil key is skipped until `cancel`: that run, in flight or
/// done, already answers it through its own `deliver`. A failure forgets the key, so the next run
/// tries again.
@MainActor
final class SupersedingTask<Key: Equatable, Value: Sendable> {
  private var generation: UInt = 0
  private var task: Task<Void, Never>?
  private var key: Key?

  func run(
    key: Key?,
    _ work: @escaping @MainActor () async throws -> Value,
    deliver: @escaping @MainActor (Result<Value, Error>) -> Void,
  ) {
    if key != nil, key == self.key { return }
    cancel()
    self.key = key
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
      if case .failure = result { self.key = nil }
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
    key = nil
  }
}
