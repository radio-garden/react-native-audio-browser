import Foundation
import Testing

@testable import AudioBrowserTestable

/// The Siri play-media intent's search under `withDeadline`, as `MediaIntentHandler` runs it. The
/// work's own cancellation guard stands in for `MediaIntentPlayback.livePlayer(of:)`, which needs
/// the bridge and is not compiled here. A search
/// answering in the same main-actor turn as the deadline is not covered: `withDeadline` settles both
/// on the main actor, cancelling before it answers nil.
@MainActor
@Suite("MediaIntentDeadline")
struct MediaIntentDeadlineTests {
  private let deadline: Duration = .milliseconds(50)
  private let found = [Track(src: "one.mp3", title: "One")]

  @MainActor
  final class Intent {
    var played: [Track] = []
    private let sleep: @Sendable (ContinuousClock.Instant) async throws -> Void

    init(sleep: @escaping @Sendable (ContinuousClock.Instant) async throws -> Void = {
      try await Task.sleep(until: $0, clock: .continuous)
    }) {
      self.sleep = sleep
    }

    func answer(within timeout: Duration, search: @escaping @MainActor () async -> [Track]?) async -> Bool? {
      await withDeadline(timeout, sleep: sleep) { _ in
        guard let tracks = await search(), !Task.isCancelled else { return false }
        self.played = tracks
        return true
      }
    }
  }

  /// A deadline that passes when the test calls `pass()`, however long the main actor is held up.
  final class HeldDeadline: @unchecked Sendable {
    private let lock = NSLock()
    private var passed = false
    private var sleeper: CheckedContinuation<Void, Error>?

    @Sendable func sleep(until _: ContinuousClock.Instant) async throws {
      try await withTaskCancellationHandler {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
          let now: Result<Void, Error>? = lock.withLock {
            if passed { return .success(()) }
            if Task.isCancelled { return .failure(CancellationError()) }
            sleeper = continuation
            return nil
          }
          if let now { continuation.resume(with: now) }
        }
      } onCancel: {
        take()?.resume(throwing: CancellationError())
      }
    }

    func pass() {
      lock.withLock { passed = true }
      take()?.resume()
    }

    private func take() -> CheckedContinuation<Void, Error>? {
      lock.withLock {
        defer { sleeper = nil }
        return sleeper
      }
    }
  }

  /// Lets `wait()` through once `open()` has been called.
  @MainActor
  final class Latch {
    private var opened = false
    private var waiter: CheckedContinuation<Void, Never>?

    func open() {
      opened = true
      waiter?.resume()
      waiter = nil
    }

    func wait() async {
      guard !opened else { return }
      await withCheckedContinuation { waiter = $0 }
    }
  }

  @Test(.timeLimit(.minutes(1))) func aSilentSearchFailsAtTheDeadline() async {
    let held = HeldDeadline()
    let intent = Intent(sleep: held.sleep)
    let answering = Task {
      await intent.answer(within: deadline) {
        try? await awaitCallback { (_: @escaping @Sendable (Result<[Track]?, Error>) -> Void) in }
      }
    }
    held.pass()
    #expect(await answering.value == nil)
  }

  @Test(.timeLimit(.minutes(1))) func aSearchAnsweringAfterTheDeadlinePlaysNothing() async {
    let held = HeldDeadline()
    let intent = Intent(sleep: held.sleep)
    let found = found
    let answered = Latch(), returned = Latch()
    let answering = Task {
      await intent.answer(within: deadline) {
        // Ignores cancellation, as a search whose result is already in hand does.
        await answered.wait()
        returned.open()
        return found
      }
    }
    held.pass()
    #expect(await answering.value == nil)
    answered.open()
    await returned.wait()
    #expect(intent.played.isEmpty)
  }

  @Test func aSearchAnsweringInTimePlays() async {
    let intent = Intent()
    let found = found
    let answer = await intent.answer(within: .seconds(5)) { found }
    #expect(answer == true)
    #expect(intent.played == found)
  }

  @Test func theWorkIsGivenTheDeadline() async {
    let before = ContinuousClock.now
    let given = await withDeadline(.seconds(5)) { $0 }
    #expect(given.map { before.duration(to: $0) >= .seconds(5) } == true)
    #expect(given.map { ContinuousClock.now.duration(to: $0) <= .seconds(5) } == true)
  }
}
