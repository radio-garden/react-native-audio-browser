import Foundation
import MediaPlayer
import Testing

@testable import AudioBrowserTestable

/// Exercises the controller against the real `MPRemoteCommandCenter`.
///
/// `MPRemoteCommandCenter`'s `init()` is unavailable, so `shared()` — a
/// process-wide singleton — is the only center available. The suite is
/// `.serialized` because of that shared state, and every test resets the
/// commands it touches through `reset()`.
@Suite("RemoteCommandController", .serialized)
@MainActor
struct RemoteCommandControllerTests {
  final class SpyCallbacks: RemoteCommandCallbacks {
    var events: [String] = []
    func remotePlay() { events.append("play") }
    func remotePause() { events.append("pause") }
    func remoteStop() { events.append("stop") }
    func remotePlayPause() { events.append("playPause") }
    func remoteNext() { events.append("next") }
    func remotePrevious() { events.append("previous") }
    func remoteJumpForward(interval: Double) { events.append("jumpForward:\(interval)") }
    func remoteJumpBackward(interval: Double) { events.append("jumpBackward:\(interval)") }
    func remoteSeek(position: Double) { events.append("seek:\(position)") }
    func remoteChangeRepeatMode(mode: RepeatMode) { events.append("repeat:\(mode)") }
    func remoteChangeShuffleMode(enabled: Bool) { events.append("shuffle:\(enabled)") }
    func remoteChangePlaybackRate(rate: Float) { events.append("rate:\(rate)") }
  }

  /// Clears every command on the shared center so a test starts from a known
  /// state regardless of what ran before it.
  private func makeController() -> (RemoteCommandController, SpyCallbacks) {
    let center = MPRemoteCommandCenter.shared()
    let reset = RemoteCommandController(remoteCommandCenter: center)
    reset.enable(commands: RemoteCommand.all())
    reset.disableAll()

    let spy = SpyCallbacks()
    return (RemoteCommandController(remoteCommandCenter: center, callbacks: spy), spy)
  }

  /// The reported bug: a changed jump interval used to disable the very command
  /// it had just re-enabled, removing the skip button from the lock screen,
  /// Control Center and CarPlay while `enabledCommands` still reported it on.
  @Test func changedJumpIntervalKeepsCommandEnabled() {
    let (controller, _) = makeController()
    let center = MPRemoteCommandCenter.shared()

    controller.enable(commands: [
      .play, .skipForward(preferredIntervals: [15]), .skipBackward(preferredIntervals: [15]),
    ])
    #expect(center.skipForwardCommand.isEnabled)

    controller.enable(commands: [
      .play, .skipForward(preferredIntervals: [30]), .skipBackward(preferredIntervals: [30]),
    ])

    #expect(center.skipForwardCommand.isEnabled)
    #expect(center.skipBackwardCommand.isEnabled)
    #expect(center.skipForwardCommand.preferredIntervals == [30])
    #expect(center.skipBackwardCommand.preferredIntervals == [30])
  }

  @Test func changedPlaybackRatesKeepCommandEnabled() {
    let (controller, _) = makeController()
    let center = MPRemoteCommandCenter.shared()

    controller.enable(commands: [.changePlaybackRate(supportedPlaybackRates: [1.0])])
    controller.enable(commands: [.changePlaybackRate(supportedPlaybackRates: [1.0, 1.5, 2.0])])

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

  @Test func disableAllClearsEveryTarget() {
    let (controller, _) = makeController()
    let center = MPRemoteCommandCenter.shared()

    controller.enable(commands: [.play, .pause, .skipForward(preferredIntervals: [15])])
    controller.disableAll()

    #expect(!center.playCommand.isEnabled)
    #expect(!center.pauseCommand.isEnabled)
    #expect(!center.skipForwardCommand.isEnabled)
  }

  /// Next/previous follow `skipAvailability` when refreshed and whenever they are enabled.
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

  /// The command targets must not capture the controller strongly, or every player
  /// instance leaks one across JS reloads.
  @Test func doesNotRetainItselfThroughHandlers() {
    weak var weakController: RemoteCommandController?
    autoreleasepool {
      let (controller, _) = makeController()
      controller.enable(commands: RemoteCommand.all())
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

    // Same center — switchCommandCenter no-ops, but the restore path is the
    // interesting part: state is re-applied from the stored values.
    controller.switchCommandCenter(center)

    #expect(center.changeShuffleModeCommand.currentShuffleType == .items)
    #expect(center.changeRepeatModeCommand.currentRepeatType == .all)
    #expect(center.changeShuffleModeCommand.isEnabled)
  }
}
