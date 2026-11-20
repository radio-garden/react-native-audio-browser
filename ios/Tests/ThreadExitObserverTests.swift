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
    }

    func start() {
      thread.start()
    }

    func finish() {
      gate.signal()
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

  /// Gives a thread that was told to finish the time to exit and the main queue a turn.
  func settle(until done: () -> Bool = { false }) async throws {
    let deadline = ContinuousClock.now + .seconds(1)
    while !done(), ContinuousClock.now < deadline {
      try await Task.sleep(for: .milliseconds(10))
    }
  }

  @Test func runsOnTheMainActorOnceItsThreadExits() async throws {
    let held = HeldThread()
    let exits = Exits()
    let observer = ThreadExitObserver(held.thread) { exits.record() }
    held.start()
    try await settle()
    #expect(exits.count == 0)

    held.finish()
    try await settle { exits.count > 0 }
    #expect(exits.count == 1)
    #expect(exits.ranOnMain)
    withExtendedLifetime(observer) {}
  }

  @Test func anotherThreadExitingDoesNotRunIt() async throws {
    let held = HeldThread()
    let other = HeldThread()
    let exits = Exits()
    let observer = ThreadExitObserver(held.thread) { exits.record() }
    held.start()
    other.start()
    other.finish()
    try await settle()
    #expect(exits.count == 0)

    held.finish()
    try await settle { exits.count > 0 }
    #expect(exits.count == 1)
    withExtendedLifetime(observer) {}
  }

  @Test func aReleasedObserverDoesNotRun() async throws {
    let held = HeldThread()
    let exits = Exits()
    var observer: ThreadExitObserver? = ThreadExitObserver(held.thread) { exits.record() }
    held.start()
    observer = nil
    held.finish()
    try await settle()
    #expect(exits.count == 0)
    #expect(observer == nil)
  }
}
