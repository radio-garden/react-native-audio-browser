import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("OnceValue")
struct OnceValueTests {
  @Test func waitReturnsImmediatelyWhenAlreadyResolved() async {
    let once = OnceValue<Int>()
    once.resolve(7)
    let value = await once.wait()
    #expect(value == 7)
  }

  @Test func waitResumesAllWaitersOnResolve() async {
    let once = OnceValue<String>()
    async let first = once.wait()
    async let second = once.wait()
    once.resolve("ready")
    let values = await [first, second]
    #expect(values == ["ready", "ready"])
  }

  @Test func checkResolvesViaProvider() async {
    nonisolated(unsafe) var ready: Int?
    let once = OnceValue<Int> { ready }
    once.check()
    ready = 3
    once.check()
    let value = await once.wait()
    #expect(value == 3)
  }

  @Test func firstResolveWins() async {
    let once = OnceValue<Int>()
    once.resolve(1)
    once.resolve(2)
    let value = await once.wait()
    #expect(value == 1)
  }
}

@Suite("OnceValue.wait(timeout:)")
struct OnceValueTimeoutTests {
  @Test func returnsValueWhenAlreadyResolved() async {
    let once = OnceValue<Int>()
    once.resolve(42)
    let value = await once.wait(timeout: .milliseconds(50))
    #expect(value == 42)
  }

  @Test func returnsValueWhenResolvedDuringWait() async {
    let once = OnceValue<Int>()
    Task {
      try? await Task.sleep(for: .milliseconds(10))
      once.resolve(42)
    }
    let value = await once.wait(timeout: .seconds(5))
    #expect(value == 42)
  }

  @Test func returnsNilOnTimeout() async {
    let once = OnceValue<Int>()
    let value = await once.wait(timeout: .milliseconds(50))
    #expect(value == nil)
  }

  @Test func lateResolveAfterTimeoutIsHarmless() async {
    let once = OnceValue<Int>()
    let value = await once.wait(timeout: .milliseconds(20))
    #expect(value == nil)
    // The timed-out waiter's continuation is still registered; resolving must
    // not double-resume it, and fresh waits must see the value.
    once.resolve(9)
    try? await Task.sleep(for: .milliseconds(20))
    let late = await once.wait(timeout: .milliseconds(50))
    #expect(late == 9)
  }
}
