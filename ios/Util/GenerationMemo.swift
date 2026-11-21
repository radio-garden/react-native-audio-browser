/// A value kept per generation and shared by its callers.
///
/// `invalidate()` starts a new generation. A caller whose computation finishes or fails after
/// an invalidation computes again, so `value(_:)` never returns a stale result; `compute` must
/// therefore read the state it depends on when it runs, not capture it. A failure is not kept:
/// the next caller of the same generation tries again. A cancelled caller leaves at once and stops
/// retrying; the computation runs on for the others.
///
/// A caller joins the newest run of its generation only while that run is younger than
/// `shareFor`; past that it starts another, so one silent computation does not hold every later
/// caller. The current generation's first value releases the callers of every run, earlier
/// generations' included; a run's failure releases only its own.
@MainActor
final class GenerationMemo<Value: Sendable> {
  private final class Run {
    let generation: Int
    let started: ContinuousClock.Instant
    private var result: Result<Value, Error>?
    private var waiters: [@Sendable (Result<Value, Error>) -> Void] = []

    init(generation: Int, started: ContinuousClock.Instant) {
      self.generation = generation
      self.started = started
    }

    func subscribe(_ deliver: @escaping @Sendable (Result<Value, Error>) -> Void) {
      if let result { deliver(result) } else { waiters.append(deliver) }
    }

    func settle(_ result: Result<Value, Error>) {
      guard self.result == nil else { return }
      self.result = result
      let waiters = waiters
      self.waiters = []
      for waiter in waiters {
        waiter(result)
      }
    }
  }

  private let shareFor: Duration
  private let now: () -> ContinuousClock.Instant
  private var generation = 0
  private var kept: Value?
  /// Runs of every generation still computing and not yet released, oldest first.
  private var running: [Run] = []

  init(
    shareFor: Duration = callbackFallbackTimeout,
    now: @escaping () -> ContinuousClock.Instant = { ContinuousClock.now },
  ) {
    self.shareFor = shareFor
    self.now = now
  }

  func invalidate() {
    generation += 1
    kept = nil
  }

  func value(_ compute: @escaping @MainActor () async throws -> Value) async throws -> Value {
    while true {
      try Task.checkCancellation()
      if let kept { return kept }
      let generation = generation
      let newest = running.last { $0.generation == generation }
      let run = newest.flatMap { now() - $0.started < shareFor ? $0 : nil } ?? start(compute)
      do {
        let value = try await awaitCallback { deliver in
          MainActor.assumeIsolated { run.subscribe(deliver) }
        }
        if generation == self.generation { return value }
      } catch {
        if generation == self.generation { throw error }
      }
    }
  }

  private func start(_ compute: @escaping @MainActor () async throws -> Value) -> Run {
    let run = Run(generation: generation, started: now())
    running.append(run)
    Task { [weak self] in
      let result: Result<Value, Error>
      do {
        result = try await .success(compute())
      } catch {
        result = .failure(error)
      }
      self?.finish(run, result)
    }
    return run
  }

  private func finish(_ run: Run, _ result: Result<Value, Error>) {
    guard run.generation == generation, kept == nil, case let .success(value) = result else {
      running.removeAll { $0 === run }
      run.settle(result)
      return
    }
    kept = value
    let released = running
    running = []
    for run in released {
      run.settle(.success(value))
    }
  }
}
