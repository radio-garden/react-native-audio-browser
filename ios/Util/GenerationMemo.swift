/// A value computed once per generation and shared by every caller of that generation.
///
/// `invalidate()` starts a new generation. A caller whose computation finishes or fails after
/// an invalidation computes again, so `value(_:)` never returns a stale result; `compute` must
/// therefore read the state it depends on when it runs, not capture it. A failure is not kept:
/// the next caller of the same generation tries again. A cancelled caller leaves at once and stops
/// retrying; the computation runs on for the others.
///
/// A caller joins the newest run only while it is younger than `shareFor`; past that it starts
/// another, so one silent computation does not hold every later caller. The generation's first
/// value releases the callers of every run, and a run's failure only its own.
@MainActor
final class GenerationMemo<Value: Sendable> {
  private final class Run {
    let started: ContinuousClock.Instant
    var waiters: [@Sendable (Result<Value, Error>) -> Void] = []

    init(started: ContinuousClock.Instant) {
      self.started = started
    }
  }

  private let shareFor: Duration
  private let now: () -> ContinuousClock.Instant
  private var generation = 0
  private var kept: Value?
  /// The current generation's runs still computing, oldest first.
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
    running = []
  }

  func value(_ compute: @escaping @MainActor () async throws -> Value) async throws -> Value {
    while true {
      try Task.checkCancellation()
      if let kept { return kept }
      let generation = generation
      let run = running.last.flatMap { now() - $0.started < shareFor ? $0 : nil }
        ?? start(compute)
      do {
        let value = try await awaitCallback { deliver in
          MainActor.assumeIsolated { run.waiters.append(deliver) }
        }
        if generation == self.generation { return value }
      } catch {
        if generation == self.generation { throw error }
      }
    }
  }

  private func start(_ compute: @escaping @MainActor () async throws -> Value) -> Run {
    let run = Run(started: now())
    let generation = generation
    running.append(run)
    Task {
      let result: Result<Value, Error>
      do {
        result = try await .success(compute())
      } catch {
        result = .failure(error)
      }
      finish(run, generation, result)
    }
    return run
  }

  private func finish(_ run: Run, _ generation: Int, _ result: Result<Value, Error>) {
    var released = [run]
    if generation == self.generation {
      if case let .success(value) = result, kept == nil {
        kept = value
        released = running
        running = []
      } else {
        running.removeAll { $0 === run }
      }
    }
    let delivered = kept.map { Result.success($0) } ?? result
    for waiter in released.flatMap(\.waiters) {
      waiter(delivered)
    }
  }
}
