@testable import AudioBrowserTestable
import Testing

@Suite("runPlayerCommand")
struct PlayerCommandTests {
  @Test func dropsTheCommandWithAWarningNamingTheCall() {
    var warnings: [String] = []
    var ran = false

    runPlayerCommand("setQueue", on: nil as Int?, warn: { warnings.append($0) }) { _ in ran = true }

    #expect(!ran)
    #expect(warnings == ["setQueue before the player is set up: ignored"])
  }

  @Test func runsTheCommandOnTheTargetWithoutAWarning() {
    var warnings: [String] = []
    var received: Int?

    runPlayerCommand("setQueue", on: 7, warn: { warnings.append($0) }) { received = $0 }

    #expect(received == 7)
    #expect(warnings.isEmpty)
  }

  @Test func rethrowsWhatTheCommandThrows() {
    struct Refused: Error {}

    #expect(throws: Refused.self) {
      try runPlayerCommand("add", on: 7, warn: { _ in }) { _ in throw Refused() }
    }
  }
}
