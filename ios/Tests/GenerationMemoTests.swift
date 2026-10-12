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

    /// Yields until `count` runs have started, recording an issue rather than hanging when they
    /// never do.
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
}
