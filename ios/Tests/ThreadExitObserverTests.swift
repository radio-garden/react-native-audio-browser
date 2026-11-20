import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("ThreadExitObserver")
@MainActor
struct ThreadExitObserverTests {
  final class HeldThread {
    private let gate = DispatchSemaphore(value: 0)
    let thread: Thread

    init() {
      let gate = gate
      thread = Thread { gate.wait() }
      thread.start()
    }

    func finish() {
      gate.signal()
    }

    /// Blocks the main actor until the thread has exited, so an exit hop is queued but not run.
    func finishAndJoin() {
      finish()
      while !thread.isFinished {
        usleep(1000)
      }
    }
  }

  @MainActor final class Exits {
    var count = 0
    var ranOnMain = true

    func record() {
      count += 1
      ranOnMain = ranOnMain && Thread.isMainThread
    }
  }

  /// Gives the main queue its turns until `done`, or a fixed time when nothing should happen.
  func settle(until done: () -> Bool = { false }, limit: Duration = .milliseconds(200)) async throws {
    let deadline = ContinuousClock.now + limit
    while !done(), ContinuousClock.now < deadline {
      try await Task.sleep(for: .milliseconds(5))
    }
  }

  @Test func runsOnTheMainActorOnceItsThreadExits() async throws {
    let held = HeldThread()
    let exits = Exits()
    let observer = ThreadExitObserver(held.thread) { exits.record() }
    try await settle()
    #expect(exits.count == 0)

    held.finish()
    try await settle(until: { exits.count > 0 }, limit: .seconds(2))
    #expect(exits.count == 1)
    #expect(exits.ranOnMain)
    withExtendedLifetime(observer) {}
  }

  @Test func anotherThreadExitingDoesNotRunIt() async throws {
    let held = HeldThread()
    let other = HeldThread()
    let exits = Exits()
    let observer = ThreadExitObserver(held.thread) { exits.record() }
    other.finishAndJoin()
    try await settle()
    #expect(exits.count == 0)
    withExtendedLifetime(observer) {}
    held.finish()
  }

  @Test func aReleasedObserverDoesNotRun() async throws {
    let held = HeldThread()
    let exits = Exits()
    _ = ThreadExitObserver(held.thread) { exits.record() }
    held.finishAndJoin()
    try await settle()
    #expect(exits.count == 0)
  }

  @Test func anObserverReleasedAfterTheExitBeforeItsHopDoesNotRun() async throws {
    let held = HeldThread()
    let exits = Exits()
    var observer: ThreadExitObserver? = ThreadExitObserver(held.thread) { exits.record() }
    held.finishAndJoin()
    withExtendedLifetime(observer) {}
    observer = nil
    try await settle()
    #expect(exits.count == 0)
  }
}
