import Testing

@testable import AudioBrowserTestable

@Suite("VirtualClock")
@MainActor
struct VirtualClockTests {
  @MainActor
  final class Woken {
    var atMs: [Int] = []
  }

  /// RetryLoop sleeps through `RetryClock`: off the main actor, the sleep would register where
  /// `run`'s yields do not wait for it.
  @Test func aSleepThroughRetryClockRegistersOnTheMainActor() async {
    let clock = VirtualClock()
    let retryClock: any RetryClock = clock
    let woken = Woken()
    Task {
      for _ in 0 ..< 2 {
        guard await (try? retryClock.sleep(ms: 100)) != nil else { return }
        woken.atMs.append(clock.nowMs)
      }
    }
    await clock.run(until: 1_000)
    #expect(woken.atMs == [100, 200])
  }
}
