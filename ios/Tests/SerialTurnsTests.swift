import Testing

@testable import AudioBrowserTestable

@Suite("SerialTurns", .timeLimit(.minutes(1)))
struct SerialTurnsTests {
  @MainActor
  final class Log {
    var entries: [String] = []
    private var gate: CheckedContinuation<Void, Never>?

    /// Suspends until `open`, as a `setCategory` round trip does.
    func wait() async {
      await withCheckedContinuation { gate = $0 }
    }

    var isWaiting: Bool {
      gate != nil
    }

    func open() {
      gate?.resume()
      gate = nil
    }
  }

  @MainActor
  @Test func aTurnWaitsForTheSuspendedOneBefore() async {
    let turns = SerialTurns()
    let log = Log()
    turns.enqueue {
      log.entries.append("first started")
      await log.wait()
      log.entries.append("first done")
    }
    let second = turns.enqueue { log.entries.append("second") }
    for _ in 0 ..< 1000 where !log.isWaiting {
      await Task.yield()
    }
    #expect(log.entries == ["first started"])
    log.open()
    await second.value
    #expect(log.entries == ["first started", "first done", "second"])
  }

  /// Earlier jobs suspend longer, so jobs that only started in order would finish in reverse.
  @Test func turnsClaimedOffTheMainActorFinishInCallOrder() async {
    let turns = SerialTurns()
    let log = await Log()
    var last: Task<Void, Never>?
    for index in 0 ..< 50 {
      last = turns.enqueue {
        for _ in index ..< 50 {
          await Task.yield()
        }
        log.entries.append("\(index)")
      }
    }
    await last?.value
    #expect(await log.entries == (0 ..< 50).map { "\($0)" })
  }
}
