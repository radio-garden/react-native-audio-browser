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
}
