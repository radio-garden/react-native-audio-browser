import Testing

@testable import AudioBrowserTestable

@MainActor
@Suite("SupersedingTask")
struct SupersedingTaskTests {
  /// Work that never answers unless cancelled, recording how each run ended.
  @MainActor
  final class SilentWork {
    var started = 0
    var cancelled = 0

    func run() async throws -> Int {
      started += 1
      do {
        return try await awaitCallback { (_: @escaping @Sendable (Result<Int, Error>) -> Void) in }
      } catch {
        if error is CancellationError { cancelled += 1 }
        throw error
      }
    }
  }

  private func yieldUntil(_ condition: () -> Bool, sourceLocation: SourceLocation = #_sourceLocation) async {
    for _ in 0 ..< 1000 where !condition() {
      await Task.yield()
    }
    #expect(condition(), sourceLocation: sourceLocation)
  }

  @Test func aNewRunCancelsTheSilentOneBefore() async {
    let task = SupersedingTask<String, Int>()
    let work = SilentWork()
    var delivered: [Result<Int, Error>] = []
    task.run(key: nil) { try await work.run() } deliver: { delivered.append($0) }
    await yieldUntil { work.started == 1 }
    task.run(key: nil) { try await work.run() } deliver: { delivered.append($0) }
    await yieldUntil { work.cancelled == 1 }
    #expect(work.started == 2)
    #expect(delivered.isEmpty)
    task.cancel()
    await yieldUntil { work.cancelled == 2 }
  }

  @Test func cancelEndsTheRunWithoutDelivering() async {
    let task = SupersedingTask<String, Int>()
    let work = SilentWork()
    var delivered = 0
    task.run(key: nil) { try await work.run() } deliver: { _ in delivered += 1 }
    await yieldUntil { work.started == 1 }
    task.cancel()
    await yieldUntil { work.cancelled == 1 }
    for _ in 0 ..< 10 {
      await Task.yield()
    }
    #expect(delivered == 0)
  }

  @Test func deliversOnlyTheLatestResult() async {
    let task = SupersedingTask<String, Int>()
    var resumeFirst: CheckedContinuation<Void, Never>?
    var delivered: [Int] = []
    task.run(key: nil) {
      // Ignores cancellation, so its answer arrives after it was superseded.
      await withCheckedContinuation { resumeFirst = $0 }
      return 1
    } deliver: { delivered.append(try! $0.get()) }
    await yieldUntil { resumeFirst != nil }
    task.run(key: nil) { 2 } deliver: { delivered.append(try! $0.get()) }
    await yieldUntil { delivered == [2] }
    resumeFirst?.resume()
    for _ in 0 ..< 10 {
      await Task.yield()
    }
    #expect(delivered == [2])
  }

  @Test func aRunForTheLatestKeyIsSkipped() async {
    let task = SupersedingTask<String, Int>()
    let work = SilentWork()
    task.run(key: "a") { try await work.run() } deliver: { _ in }
    task.run(key: "a") { try await work.run() } deliver: { _ in }
    await yieldUntil { work.started == 1 }
    task.run(key: "b") { try await work.run() } deliver: { _ in }
    await yieldUntil { work.started == 2 }
    #expect(work.cancelled == 1)
    task.cancel()
  }

  @Test func aDeliveredKeyIsSkippedUntilCancel() async {
    let task = SupersedingTask<String, Int>()
    var runs = 0
    var delivered: [Int] = []
    task.run(key: "a") { runs += 1; return 1 } deliver: { delivered.append(try! $0.get()) }
    await yieldUntil { delivered == [1] }
    task.run(key: "a") { runs += 1; return 2 } deliver: { delivered.append(try! $0.get()) }
    for _ in 0 ..< 10 {
      await Task.yield()
    }
    #expect(runs == 1)
    task.cancel()
    task.run(key: "a") { runs += 1; return 3 } deliver: { delivered.append(try! $0.get()) }
    await yieldUntil { delivered == [1, 3] }
    #expect(runs == 2)
  }

  @Test func aFailedKeyRunsAgain() async {
    struct Failure: Error {}
    let task = SupersedingTask<String, Int>()
    var runs = 0
    var failed = false
    task.run(key: "a") { runs += 1; throw Failure() } deliver: { _ in failed = true }
    await yieldUntil { failed }
    task.run(key: "a") { runs += 1; return 1 } deliver: { _ in }
    await yieldUntil { runs == 2 }
  }

  @Test func aNilKeyAlwaysRuns() async {
    let task = SupersedingTask<String, Int>()
    let work = SilentWork()
    task.run(key: nil) { try await work.run() } deliver: { _ in }
    await yieldUntil { work.started == 1 }
    task.run(key: nil) { try await work.run() } deliver: { _ in }
    await yieldUntil { work.started == 2 }
    task.cancel()
  }

  @Test func deliversAFailure() async {
    struct Failure: Error {}
    let task = SupersedingTask<String, Int>()
    var failed = false
    task.run(key: nil) { throw Failure() } deliver: { result in
      if case .failure(is Failure) = result { failed = true }
    }
    await yieldUntil { failed }
  }
}
