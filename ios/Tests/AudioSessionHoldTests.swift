@testable import AudioBrowserTestable
import Testing

@Suite("AudioSessionHold")
struct AudioSessionHoldTests {
  struct Step: CustomTestStringConvertible, Sendable {
    let name: String
    let engaged: Bool
    let hasTrack: Bool
    let terminal: Bool
    let playWhenReady: Bool
    let engagedAfter: Bool
    var testDescription: String { name }
  }

  @Test(arguments: [
    Step(
      name: "a play intent over a playable track takes the hold",
      engaged: false, hasTrack: true, terminal: false, playWhenReady: true, engagedAfter: true,
    ),
    Step(
      name: "a pause keeps the hold",
      engaged: true, hasTrack: true, terminal: false, playWhenReady: false, engagedAfter: true,
    ),
    Step(
      name: "a track loaded without an intent does not retake the hold",
      engaged: false, hasTrack: true, terminal: false, playWhenReady: false, engagedAfter: false,
    ),
    Step(
      name: "a terminal state gives the hold up, the intent held",
      engaged: true, hasTrack: true, terminal: true, playWhenReady: true, engagedAfter: false,
    ),
    Step(
      name: "a terminal state gives the hold up, the intent down",
      engaged: true, hasTrack: true, terminal: true, playWhenReady: false, engagedAfter: false,
    ),
    Step(
      name: "an empty queue gives the hold up, the intent held",
      engaged: true, hasTrack: false, terminal: false, playWhenReady: true, engagedAfter: false,
    ),
    Step(
      name: "a play with nothing queued takes no hold",
      engaged: false, hasTrack: false, terminal: false, playWhenReady: true, engagedAfter: false,
    ),
    Step(
      name: "an intent held over a terminal state takes no hold",
      engaged: false, hasTrack: true, terminal: true, playWhenReady: true, engagedAfter: false,
    ),
  ])
  func step(_ step: Step) {
    var hold = AudioSessionHold(engaged: step.engaged)
    hold.step(hasTrack: step.hasTrack, terminal: step.terminal, playWhenReady: step.playWhenReady)
    #expect(hold == AudioSessionHold(engaged: step.engagedAfter))
  }

  @Test func anEngagedHoldHolds() {
    #expect(AudioSessionHold(engaged: true).holds(resumingAfterInterruption: false))
  }

  @Test func anInterruptionThatMayResumeHoldsWithoutTheLatch() {
    #expect(AudioSessionHold(engaged: false).holds(resumingAfterInterruption: true))
  }

  @Test func nothingToResumeAndNoLatchHoldsNothing() {
    #expect(!AudioSessionHold(engaged: false).holds(resumingAfterInterruption: false))
  }
}
