import Foundation
import Testing

@testable import AudioBrowserTestable

@MainActor
@Suite("Wakeups")
struct WakeupsTests {
  /// Lets the tasks started so far run until they suspend.
  private func settle() async {
    for _ in 0 ..< 10 {
      await Task.yield()
    }
  }

  @Test func aWaitEndsOnTheEventItWaitsFor() async throws {
    let wakeups = Wakeups<Int>()
    var events: [Int] = []
    let waiting = Task { @MainActor in
      try await wakeups.wait { event in
        events.append(event)
        return event == 2 ? .resume : .keepWaiting
      }
    }
    await settle()
    wakeups.post(1)
    wakeups.post(2)
    try await waiting.value
    wakeups.post(3)
    #expect(events == [1, 2])
  }

  @Test func aWaitGivenUpOnThrows() async {
    let wakeups = Wakeups<Int>()
    let waiting = Task { @MainActor in try await wakeups.wait { _ in .giveUp } }
    await settle()
    wakeups.post(1)
    await #expect(throws: Wakeups<Int>.GaveUp.self) { try await waiting.value }
    #expect(wakeups.waiterCount == 0)
  }

  @Test func aCancelledWaitThrowsAndIsForgotten() async {
    let wakeups = Wakeups<Int>()
    let waiting = Task { @MainActor in try await wakeups.wait { _ in .keepWaiting } }
    await settle()
    #expect(wakeups.waiterCount == 1)
    waiting.cancel()
    await #expect(throws: CancellationError.self) { try await waiting.value }
    #expect(wakeups.waiterCount == 0)
  }

  @Test func aWaitStartedInACancelledTaskThrowsAtOnce() async {
    let wakeups = Wakeups<Int>()
    let waiting = Task { @MainActor in
      withUnsafeCurrentTask { $0?.cancel() }
      try await wakeups.wait { _ in .keepWaiting }
    }
    await #expect(throws: CancellationError.self) { try await waiting.value }
    #expect(wakeups.waiterCount == 0)
  }

  @Test func anEventResumesOnlyTheWaitsItIsFor() async throws {
    let wakeups = Wakeups<Int>()
    let one = Task { @MainActor in try await wakeups.wait { $0 == 1 ? .resume : .keepWaiting } }
    _ = Task { @MainActor in try await wakeups.wait { $0 == 2 ? .resume : .keepWaiting } }
    await settle()
    wakeups.post(1)
    try await one.value
    #expect(wakeups.waiterCount == 1)
  }

  @Test func aJudgeThatPostsDoesNotResumeAWaitTwice() async throws {
    let wakeups = Wakeups<Int>()
    var resumed = 0
    let reposting = Task { @MainActor in
      try await wakeups.wait { event in
        if event == 1 { wakeups.post(2) }
        return .keepWaiting
      }
    }
    let other = Task { @MainActor in
      try await wakeups.wait { _ in
        resumed += 1
        return .resume
      }
      return resumed
    }
    await settle()
    wakeups.post(1)
    #expect(try await other.value == 1)
    reposting.cancel()
  }

  @Test func aWaitAfterTheEndThrowsAtOnce() async {
    let wakeups = Wakeups<Int>()
    wakeups.end()
    let waiting = Task { @MainActor in try await wakeups.wait { _ in .keepWaiting } }
    await #expect(throws: CancellationError.self) { try await waiting.value }
  }

  @Test func aWaitForARowGivesUpOnceTheRowIsGoneAndResumesOnceReady() {
    final class Row {}
    #expect(Wakeups<Int>.Verdict.waiting(for: nil, ready: true) == .giveUp)
    #expect(Wakeups<Int>.Verdict.waiting(for: Row(), ready: false) == .keepWaiting)
    #expect(Wakeups<Int>.Verdict.waiting(for: Row(), ready: true) == .resume)
  }

  @Test func endingResumesEveryWaitWithAThrow() async {
    let wakeups = Wakeups<Int>()
    let first = Task { @MainActor in try await wakeups.wait { _ in .keepWaiting } }
    let second = Task { @MainActor in try await wakeups.wait { _ in .keepWaiting } }
    await settle()
    wakeups.end()
    await #expect(throws: CancellationError.self) { try await first.value }
    await #expect(throws: CancellationError.self) { try await second.value }
  }
}
