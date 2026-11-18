import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("awaitCallback")
struct AwaitCallbackTests {
  struct Rejected: Error, Equatable {}

  /// A JS callback's pending answer: records the subscriber's `deliver`, which the test calls
  /// as JS would, on any thread and as often as it likes.
  final class FakeCallback: @unchecked Sendable {
    private let lock = NSLock()
    private var delivers: [@Sendable (Result<Int, Error>) -> Void] = []

    var subscribe: CallbackSubscription<Int> {
      { deliver in self.lock.withLock { self.delivers.append(deliver) } }
    }

    var isSubscribed: Bool {
      lock.withLock { !delivers.isEmpty }
    }

    func settle(_ result: Result<Int, Error>) {
      for deliver in lock.withLock({ delivers }) {
        deliver(result)
      }
    }

    func waitForSubscriber() async {
      while !isSubscribed {
        await Task.yield()
      }
    }
  }

  static let raceRuns = 500

  // MARK: - Without a timeout

  @Test func anAnswerResumesWithIt() async throws {
    let callback = FakeCallback()
    let result = try await withinTimeLimit {
      try await awaitCallback { deliver in
        callback.subscribe(deliver)
        callback.settle(.success(7))
      }
    }
    #expect(result == 7)
  }

  @Test func aRejectionThrowsIt() async throws {
    let callback = FakeCallback()
    await #expect(throws: Rejected.self) {
      try await withinTimeLimit {
        try await awaitCallback { deliver in
          callback.subscribe(deliver)
          callback.settle(.failure(Rejected()))
        }
      }
    }
  }

  @Test func aCancelledCallerEndsWhileJSIsSilent() async throws {
    let callback = FakeCallback()
    let waiting = Task { try await awaitCallback(callback.subscribe) }
    await callback.waitForSubscriber()
    waiting.cancel()
    await #expect(throws: CancellationError.self) {
      try await withinTimeLimit { try await waiting.value }
    }
    callback.settle(.success(1))
  }

  @Test func aCallerCancelledBeforeTheAwaitEndsAtOnce() async throws {
    let callback = FakeCallback()
    await #expect(throws: CancellationError.self) {
      try await withinTimeLimit {
        withUnsafeCurrentTask { $0?.cancel() }
        return try await awaitCallback(callback.subscribe)
      }
    }
  }

  @Test func anAnswerAndACancelTogetherResumeOnce() async throws {
    var answered = 0
    var cancelled = 0
    for _ in 0 ..< Self.raceRuns {
      let callback = FakeCallback()
      let waiting = Task { try await awaitCallback(callback.subscribe) }
      await callback.waitForSubscriber()
      let go = Flag()
      let answer = Task.detached {
        while !go.isSet {}
        callback.settle(.success(3))
      }
      let cancel = Task.detached {
        while !go.isSet {}
        waiting.cancel()
      }
      go.set()
      await answer.value
      await cancel.value
      let outcome = try await withinTimeLimit { await waiting.result }
      switch outcome {
      case let .success(value):
        #expect(value == 3)
        answered += 1
      case let .failure(error):
        #expect(error is CancellationError)
        cancelled += 1
      }
    }
    #expect(answered > 0 && cancelled > 0, "answered \(answered), cancelled \(cancelled)")
  }

  @Test func aRejectAfterACancelIsIgnored() async throws {
    for _ in 0 ..< Self.raceRuns {
      let callback = FakeCallback()
      let waiting = Task { try await awaitCallback(callback.subscribe) }
      await callback.waitForSubscriber()
      waiting.cancel()
      await Task.detached { callback.settle(.failure(Rejected())) }.value
      await #expect(throws: CancellationError.self) {
        try await withinTimeLimit { try await waiting.value }
      }
    }
  }

  // MARK: - With a timeout

  @Test func silentJSTimesOutAndNotBefore() async throws {
    let callback = FakeCallback()
    let waiting = Task { try await awaitCallback(timeout: .milliseconds(600), callback.subscribe) }
    let finished = Flag()
    Task {
      _ = await waiting.result
      finished.set()
    }
    try await Task.sleep(for: .milliseconds(200))
    #expect(!finished.isSet)
    await #expect(throws: CallbackTimeoutError.self) {
      try await withinTimeLimit { try await waiting.value }
    }
  }

  @Test func anAnswerInsideTheTimeoutIsKept() async throws {
    let callback = FakeCallback()
    let waiting = Task { try await awaitCallback(timeout: .seconds(5), callback.subscribe) }
    await callback.waitForSubscriber()
    callback.settle(.success(4))
    #expect(try await withinTimeLimit { try await waiting.value } == 4)
  }

  @Test func aCancelledCallerWithATimeoutPendingEndsWithItsCancellation() async throws {
    let callback = FakeCallback()
    let waiting = Task { try await awaitCallback(timeout: .seconds(5), callback.subscribe) }
    await callback.waitForSubscriber()
    waiting.cancel()
    await #expect(throws: CancellationError.self) {
      try await withinTimeLimit { try await waiting.value }
    }
  }

  /// Spreads each run's settle from before the timeout to after it.
  static func delay(_ run: Int) -> Duration {
    .microseconds(run * 10000 / raceRuns)
  }

  @Test func anAnswerAroundTheTimeoutResumesOnce() async throws {
    var answered = 0
    var timedOut = 0
    for run in 0 ..< Self.raceRuns {
      let callback = FakeCallback()
      let waiting = Task { try await awaitCallback(timeout: .milliseconds(5), callback.subscribe) }
      await callback.waitForSubscriber()
      try await Task.sleep(for: Self.delay(run))
      await Task.detached { callback.settle(.success(5)) }.value
      let outcome = try await withinTimeLimit { await waiting.result }
      switch outcome {
      case let .success(value):
        #expect(value == 5)
        answered += 1
      case let .failure(error):
        #expect(error is CallbackTimeoutError)
        timedOut += 1
      }
    }
    #expect(answered > 0 && timedOut > 0, "answered \(answered), timed out \(timedOut)")
  }

  @Test func aCancelAroundTheTimeoutResumesOnce() async throws {
    var cancelled = 0
    var timedOut = 0
    for run in 0 ..< Self.raceRuns {
      let callback = FakeCallback()
      let waiting = Task { try await awaitCallback(timeout: .milliseconds(5), callback.subscribe) }
      await callback.waitForSubscriber()
      try await Task.sleep(for: Self.delay(run))
      waiting.cancel()
      let outcome = try await withinTimeLimit { await waiting.result }
      switch outcome {
      case .failure(is CancellationError): cancelled += 1
      case .failure(is CallbackTimeoutError): timedOut += 1
      default: Issue.record("resumed with \(outcome)")
      }
    }
    #expect(cancelled > 0 && timedOut > 0, "cancelled \(cancelled), timed out \(timedOut)")
  }
}
