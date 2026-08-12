import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("RemoteCommand")
struct RemoteCommandTests {
  @Test func removedCommandsAreDisabled() {
    let toDisable = RemoteCommand.commandsToDisable(
      enabled: [.play, .pause, .next, .previous],
      replacedBy: [.play, .pause],
    )
    #expect(toDisable.map(\.key) == ["nextTrack", "previousTrack"])
  }

  @Test func retainedCommandsAreNotDisabled() {
    let toDisable = RemoteCommand.commandsToDisable(
      enabled: [.play, .pause],
      replacedBy: [.play, .pause, .stop],
    )
    #expect(toDisable.isEmpty)
  }

  /// A changed jump interval is the same command with a new associated value —
  /// diffing by `==` would report it as removed and disable the button that the
  /// update had just re-enabled.
  @Test func changedJumpIntervalIsNotDisabled() {
    let toDisable = RemoteCommand.commandsToDisable(
      enabled: [.skipForward(preferredIntervals: [15]), .skipBackward(preferredIntervals: [15])],
      replacedBy: [.skipForward(preferredIntervals: [30]), .skipBackward(preferredIntervals: [30])],
    )
    #expect(toDisable.isEmpty)
  }

  @Test func changedPlaybackRatesAreNotDisabled() {
    let toDisable = RemoteCommand.commandsToDisable(
      enabled: [.changePlaybackRate(supportedPlaybackRates: [1.0])],
      replacedBy: [.changePlaybackRate(supportedPlaybackRates: [1.0, 1.5, 2.0])],
    )
    #expect(toDisable.isEmpty)
  }

  @Test func everyCommandIsDisabledWhenNoneRemain() {
    let toDisable = RemoteCommand.commandsToDisable(
      enabled: RemoteCommand.all(),
      replacedBy: [],
    )
    #expect(toDisable.count == RemoteCommand.all().count)
  }

  /// `key` is the identity used both for the diff above and for the
  /// `commandTargetPointers` slot each command's handler lives in. Two cases
  /// sharing a key would silently share one slot: enabling one would remove the
  /// other's handler.
  @Test func keysAreUnique() {
    let all = RemoteCommand.all()
    #expect(Set(all.map(\.key)).count == all.count)
  }

  @Test func droppedJumpCommandIsStillDisabled() {
    let toDisable = RemoteCommand.commandsToDisable(
      enabled: [.play, .skipForward(preferredIntervals: [15])],
      replacedBy: [.play],
    )
    #expect(toDisable.map(\.key) == ["skipForward"])
  }
}
