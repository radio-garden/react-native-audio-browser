import Foundation
import MediaPlayer
import Testing

@testable import AudioBrowserTestable

/// Runs against the real `MPRemoteCommandCenter`: its `init()` is unavailable, so the process-wide
/// `shared()` singleton is the only center, hence `.serialized`.
@Suite("RemoteCommandController", .serialized)
@MainActor
struct RemoteCommandControllerTests {
  final class SpyCallbacks: RemoteCommandCallbacks {
    var events: [String] = []
    func remotePlay() { events.append("play") }
    func remotePause() { events.append("pause") }
    func remoteStop() { events.append("stop") }
    func remoteNext() { events.append("next") }
    func remotePrevious() { events.append("previous") }
    func remoteJumpForward(interval: Double) { events.append("jumpForward:\(interval)") }
    func remoteJumpBackward(interval: Double) { events.append("jumpBackward:\(interval)") }
    func remoteSeek(position: Double) { events.append("seek:\(position)") }
    func remoteChangeRepeatMode(mode: RepeatMode) { events.append("repeat:\(mode)") }
    func remoteChangeShuffleMode(enabled: Bool) { events.append("shuffle:\(enabled)") }
    func remoteChangePlaybackRate(rate: Float) { events.append("rate:\(rate)") }
  }

  private func makeController() -> (RemoteCommandController, SpyCallbacks) {
    // Clears whatever an earlier test left enabled on the shared center.
    let center = MPRemoteCommandCenter.shared()
    let reset = RemoteCommandController(remoteCommandCenter: center)
    reset.enable(commands: Set(RemoteCommand.allCases))
    reset.disableAll()

    let spy = SpyCallbacks()
    return (RemoteCommandController(remoteCommandCenter: center, callbacks: spy), spy)
  }

  @Test func changedJumpIntervalKeepsCommandEnabled() {
    let (controller, _) = makeController()
    let center = MPRemoteCommandCenter.shared()

    controller.enable(commands: [.play, .skipForward, .skipBackward])
    #expect(center.skipForwardCommand.isEnabled)

    controller.enable(
      commands: [.play, .skipForward, .skipBackward], forwardJumpInterval: 30, backwardJumpInterval: 30,
    )

    #expect(center.skipForwardCommand.isEnabled)
    #expect(center.skipBackwardCommand.isEnabled)
    #expect(center.skipForwardCommand.preferredIntervals == [30])
    #expect(center.skipBackwardCommand.preferredIntervals == [30])
  }

  @Test func changedPlaybackRatesKeepCommandEnabled() {
    let (controller, _) = makeController()
    let center = MPRemoteCommandCenter.shared()

    controller.enable(commands: [.changePlaybackRate], playbackRates: [1.0])
    controller.enable(commands: [.changePlaybackRate], playbackRates: [1.0, 1.5, 2.0])

    #expect(center.changePlaybackRateCommand.isEnabled)
    #expect(center.changePlaybackRateCommand.supportedPlaybackRates == [1.0, 1.5, 2.0])
  }

  @Test func removedCommandIsDisabledAndUntargeted() {
    let (controller, _) = makeController()
    let center = MPRemoteCommandCenter.shared()

    controller.enable(commands: [.play, .pause, .next])
    #expect(center.nextTrackCommand.isEnabled)

    controller.enable(commands: [.play, .pause])

    #expect(!center.nextTrackCommand.isEnabled)
    #expect(center.playCommand.isEnabled)
  }

  @Test func retainedCommandStaysEnabledWhenOthersAreAdded() {
    let (controller, _) = makeController()
    let center = MPRemoteCommandCenter.shared()

    controller.enable(commands: [.play, .pause])
    controller.enable(commands: [.play, .pause, .stop])

    #expect(center.playCommand.isEnabled)
    #expect(center.pauseCommand.isEnabled)
    #expect(center.stopCommand.isEnabled)
  }

  @Test func droppedJumpCommandIsDisabled() {
    let (controller, _) = makeController()
    let center = MPRemoteCommandCenter.shared()

    controller.enable(commands: [.play, .skipForward])
    controller.enable(commands: [.play])

    #expect(!center.skipForwardCommand.isEnabled)
    #expect(center.playCommand.isEnabled)
  }

  @Test func enablingNoCommandsDisablesEveryOne() {
    let (controller, _) = makeController()
    let center = MPRemoteCommandCenter.shared()

    controller.enable(commands: Set(RemoteCommand.allCases))
    controller.enable(commands: [])

    #expect(!center.playCommand.isEnabled)
    #expect(!center.skipBackwardCommand.isEnabled)
    #expect(!center.changePlaybackRateCommand.isEnabled)
  }

  @Test func disableAllClearsEveryTarget() {
    let (controller, _) = makeController()
    let center = MPRemoteCommandCenter.shared()

    controller.enable(commands: [.play, .pause, .skipForward])
    controller.disableAll()

    #expect(!center.playCommand.isEnabled)
    #expect(!center.pauseCommand.isEnabled)
    #expect(!center.skipForwardCommand.isEnabled)
  }

  @Test func skipAvailabilityAppliesOnRefreshAndEnable() {
    let (controller, _) = makeController()
    let center = MPRemoteCommandCenter.shared()
    var availability = (canNext: true, canPrevious: true)
    controller.skipAvailability = { availability }

    controller.enable(commands: [.next, .previous])
    #expect(center.nextTrackCommand.isEnabled)

    availability = (false, true)
    controller.refreshSkipAvailability()
    #expect(!center.nextTrackCommand.isEnabled)
    #expect(center.previousTrackCommand.isEnabled)

    controller.enable(commands: [.next, .previous, .play])

    #expect(!center.nextTrackCommand.isEnabled)
    #expect(center.previousTrackCommand.isEnabled)
  }

  /// Handlers capturing the controller strongly would leak one per player instance across JS reloads.
  @Test func doesNotRetainItselfThroughHandlers() {
    weak var weakController: RemoteCommandController?
    autoreleasepool {
      let (controller, _) = makeController()
      controller.enable(commands: Set(RemoteCommand.allCases))
      controller.disableAll()
      weakController = controller
    }
    #expect(weakController == nil)
  }

  @Test func shuffleAndRepeatStateSurviveCommandCenterSwitch() {
    let (controller, _) = makeController()
    let center = MPRemoteCommandCenter.shared()

    controller.enable(commands: [.changeShuffleMode, .changeRepeatMode])
    controller.updateShuffleMode(true)
    controller.updateRepeatMode(.queue)

    // The same center makes this a no-op; the expectations hold from the calls above.
    controller.switchCommandCenter(center)

    #expect(center.changeShuffleModeCommand.currentShuffleType == .items)
    #expect(center.changeRepeatModeCommand.currentRepeatType == .all)
    #expect(center.changeShuffleModeCommand.isEnabled)
  }
}
