import Testing

@testable import AudioBrowserTestable

@MainActor
@Suite("GenerationMemo")
struct GenerationMemoTests {
  struct Failure: Error {}

  /// A computation that suspends until `release()`, counting its runs.
  @MainActor
  final class Gate {
    var runs = 0
    var input = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func run() async -> Int {
      runs += 1
      let input = input
      await withCheckedContinuation { waiters.append($0) }
      return input
    }

    func release() {
      let pending = waiters
      waiters = []
      for waiter in pending {
        waiter.resume()
      }
    }

    /// Yields until `count` runs have started; when they never do, records an issue and releases
    /// the waiters so the test fails rather than hangs.
    func waitForRuns(_ count: Int, sourceLocation: SourceLocation = #_sourceLocation) async {
      for _ in 0 ..< 1000 where runs < count {
        await Task.yield()
      }
      if runs < count {
        Issue.record("expected \(count) runs, saw \(runs)", sourceLocation: sourceLocation)
        release()
      }
    }
  }

  @Test func concurrentCallersShareOneComputation() async throws {
    let memo = GenerationMemo<Int>()
    let gate = Gate()
    gate.input = 1
    let first = Task { try await memo.value { await gate.run() } }
    let second = Task { try await memo.value { await gate.run() } }
    await gate.waitForRuns(1)
    gate.release()
    #expect(try await first.value == 1)
    #expect(try await second.value == 1)
    #expect(gate.runs == 1)
  }

  @Test func keepsTheValueWithinAGeneration() async throws {
    let memo = GenerationMemo<Int>()
    _ = try await memo.value { 1 }
    #expect(try await memo.value { 2 } == 1)
  }

  @Test func invalidateComputesAgain() async throws {
    let memo = GenerationMemo<Int>()
    _ = try await memo.value { 1 }
    memo.invalidate()
    #expect(try await memo.value { 2 } == 2)
  }

  @Test func aResultThatLandsAfterAnInvalidationIsRecomputed() async throws {
    let memo = GenerationMemo<Int>()
    let gate = Gate()
    gate.input = 1
    let result = Task { try await memo.value { await gate.run() } }
    await gate.waitForRuns(1)
    gate.input = 2
    memo.invalidate()
    gate.release()
    await gate.waitForRuns(2)
    gate.release()
    #expect(try await result.value == 2)
  }

  @Test func aFailureIsNotKept() async throws {
    let memo = GenerationMemo<Int>()
    await #expect(throws: Failure.self) { try await memo.value { throw Failure() } }
    #expect(try await memo.value { 3 } == 3)
  }

  @Test func aFailureThatLandsAfterAnInvalidationIsRetried() async throws {
    let memo = GenerationMemo<Int>()
    let gate = Gate()
    let result = Task {
      try await memo.value {
        let input = await gate.run()
        if input == 0 { throw Failure() }
        return input
      }
    }
    await gate.waitForRuns(1)
    gate.input = 4
    memo.invalidate()
    gate.release()
    await gate.waitForRuns(2)
    gate.release()
    #expect(try await result.value == 4)
  }

  @Test func aCancelledCallerStopsRetrying() async throws {
    let memo = GenerationMemo<Int>()
    let gate = Gate()
    let result = Task { try await memo.value { await gate.run() } }
    await gate.waitForRuns(1)
    memo.invalidate()
    result.cancel()
    gate.release()
    await #expect(throws: CancellationError.self) { try await result.value }
    #expect(gate.runs == 1)
  }

  @Test func aCancelledCallerLeavesWhileTheComputationRunsOn() async throws {
    let memo = GenerationMemo<Int>()
    let gate = Gate()
    gate.input = 5
    let cancelled = Task { try await memo.value { await gate.run() } }
    let other = Task { try await memo.value { await gate.run() } }
    await gate.waitForRuns(1)
    cancelled.cancel()
    await #expect(throws: CancellationError.self) {
      try await withinTimeLimit { try await cancelled.value }
    }
    gate.release()
    #expect(try await withinTimeLimit { try await other.value } == 5)
    #expect(gate.runs == 1)
  }

  @Test func aCallerAfterACancelledOneJoinsTheRunningComputation() async throws {
    let memo = GenerationMemo<Int>()
    let gate = Gate()
    gate.input = 6
    let cancelled = Task { try await memo.value { await gate.run() } }
    await gate.waitForRuns(1)
    cancelled.cancel()
    _ = try await withinTimeLimit { await cancelled.result }
    let later = Task { try await memo.value { await gate.run() } }
    for _ in 0 ..< 100 {
      await Task.yield()
    }
    gate.release()
    #expect(try await withinTimeLimit { try await later.value } == 6)
    #expect(gate.runs == 1)
  }

  @Test func aFailureAfterEveryCallerLeftIsNotKept() async throws {
    let memo = GenerationMemo<Int>()
    let gate = Gate()
    let cancelled = Task {
      try await memo.value {
        let input = await gate.run()
        if input == 0 { throw Failure() }
        return input
      }
    }
    await gate.waitForRuns(1)
    cancelled.cancel()
    _ = try await withinTimeLimit { await cancelled.result }
    gate.release()
    for _ in 0 ..< 100 {
      await Task.yield()
    }
    gate.input = 7
    let later = Task { try await memo.value { await gate.run() } }
    await gate.waitForRuns(2)
    gate.release()
    #expect(try await withinTimeLimit { try await later.value } == 7)
  }

  /// Runs that each suspend until released on their own, numbered from 0 in start order.
  @MainActor
  final class Runs {
    private var waiters: [CheckedContinuation<Result<Int, Error>, Never>?] = []
    var count: Int { waiters.count }

    func run() async throws -> Int {
      try await withCheckedContinuation { waiters.append($0) }.get()
    }

    func release(_ index: Int, _ result: Result<Int, Error>) {
      guard waiters.indices.contains(index) else {
        Issue.record("no run \(index)")
        return
      }
      waiters[index]?.resume(returning: result)
      waiters[index] = nil
    }

    func waitForRuns(_ count: Int, sourceLocation: SourceLocation = #_sourceLocation) async {
      for _ in 0 ..< 1000 where self.count < count {
        await Task.yield()
      }
      if self.count < count {
        Issue.record("expected \(count) runs, saw \(self.count)", sourceLocation: sourceLocation)
      }
    }
  }

  @MainActor
  final class TestClock {
    var now = ContinuousClock.now
  }

  @Test func aCallerPastTheBoundStartsAnotherRunWhoseValueReleasesEveryCaller() async throws {
    let clock = TestClock()
    let memo = GenerationMemo<Int>(shareFor: .seconds(10), now: { clock.now })
    let runs = Runs()
    let early = Task { try await memo.value { try await runs.run() } }
    await runs.waitForRuns(1)
    clock.now += .seconds(11)
    let late = Task { try await memo.value { try await runs.run() } }
    await runs.waitForRuns(2)
    runs.release(1, .success(2))
    #expect(try await withinTimeLimit { try await late.value } == 2)
    #expect(try await withinTimeLimit { try await early.value } == 2)
    runs.release(0, .success(1))
  }

  @Test func aCallerWithinTheBoundJoinsTheRun() async throws {
    let clock = TestClock()
    let memo = GenerationMemo<Int>(shareFor: .seconds(10), now: { clock.now })
    let runs = Runs()
    let early = Task { try await memo.value { try await runs.run() } }
    await runs.waitForRuns(1)
    clock.now += .seconds(9)
    let late = Task { try await memo.value { try await runs.run() } }
    for _ in 0 ..< 100 {
      await Task.yield()
    }
    #expect(runs.count == 1)
    runs.release(0, .success(1))
    #expect(try await withinTimeLimit { try await late.value } == 1)
    #expect(try await withinTimeLimit { try await early.value } == 1)
  }

  @Test func theBoundCountsFromTheNewestRun() async throws {
    let clock = TestClock()
    let memo = GenerationMemo<Int>(shareFor: .seconds(10), now: { clock.now })
    let runs = Runs()
    let first = Task { try await memo.value { try await runs.run() } }
    await runs.waitForRuns(1)
    clock.now += .seconds(11)
    let second = Task { try await memo.value { try await runs.run() } }
    await runs.waitForRuns(2)
    clock.now += .seconds(5)
    let third = Task { try await memo.value { try await runs.run() } }
    for _ in 0 ..< 100 {
      await Task.yield()
    }
    #expect(runs.count == 2)
    runs.release(1, .success(2))
    #expect(try await withinTimeLimit { try await third.value } == 2)
    #expect(try await withinTimeLimit { try await first.value } == 2)
    _ = try await second.value
    runs.release(0, .success(1))
  }

  @Test func theFirstValueOfAGenerationIsKeptOverALaterRunsValue() async throws {
    let clock = TestClock()
    let memo = GenerationMemo<Int>(shareFor: .seconds(10), now: { clock.now })
    let runs = Runs()
    let early = Task { try await memo.value { try await runs.run() } }
    await runs.waitForRuns(1)
    clock.now += .seconds(11)
    let late = Task { try await memo.value { try await runs.run() } }
    await runs.waitForRuns(2)
    runs.release(1, .success(2))
    _ = try await withinTimeLimit { try await late.value }
    _ = try await withinTimeLimit { try await early.value }
    runs.release(0, .success(1))
    for _ in 0 ..< 100 {
      await Task.yield()
    }
    #expect(try await memo.value { 3 } == 2)
  }

  @Test func aLaterRunsFailureLeavesTheEarlierRunsCallersWaiting() async throws {
    let clock = TestClock()
    let memo = GenerationMemo<Int>(shareFor: .seconds(10), now: { clock.now })
    let runs = Runs()
    let early = Task { try await memo.value { try await runs.run() } }
    await runs.waitForRuns(1)
    clock.now += .seconds(11)
    let late = Task { try await memo.value { try await runs.run() } }
    await runs.waitForRuns(2)
    runs.release(1, .failure(Failure()))
    await #expect(throws: Failure.self) { try await withinTimeLimit { try await late.value } }
    runs.release(0, .success(1))
    #expect(try await withinTimeLimit { try await early.value } == 1)
  }
}
