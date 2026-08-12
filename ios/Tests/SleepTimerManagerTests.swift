@testable import AudioBrowserTestable
import Foundation
import Testing

// Timing tests drive a ManualSleepTimerScheduler rather than the main queue:
// every deadline below is virtual, so nothing races the real clock.

@Suite("SleepTimerManager - fade scheduling")
struct SleepTimerManagerTests {
  @Test @MainActor
  func fadeFiresBeforeDeadline_completionDoesNotEmitFadeCancel() {
    let clock = ManualSleepTimerScheduler()
    let manager = SleepTimerManager(scheduler: clock)
    var fadeStarts: [TimeInterval] = []
    var completes = 0
    var cancels = 0
    manager.onFadeStart = { fadeStarts.append($0) }
    manager.onComplete = { completes += 1 }
    manager.set(seconds: 0.3, fadeDuration: 0.15)
    // Attach after set() — replacing a timer emits a defensive onFadeCancel.
    manager.onFadeCancel = { cancels += 1 }

    clock.advance(to: 0.05) // before the fade window
    #expect(fadeStarts.isEmpty)
    #expect(manager.get() != nil)

    clock.advance(to: 0.22) // inside the fade window
    #expect(fadeStarts == [0.15])
    #expect(completes == 0)

    clock.advance(to: 0.4) // past the deadline
    #expect(completes == 1)
    #expect(cancels == 0)
    #expect(manager.get() == nil)
  }

  @Test @MainActor
  func fadeLongerThanTimer_clampsAndStartsImmediately() {
    let clock = ManualSleepTimerScheduler()
    let manager = SleepTimerManager(scheduler: clock)
    var fadeStarts: [TimeInterval] = []
    manager.onFadeStart = { fadeStarts.append($0) }

    manager.set(seconds: 0.2, fadeDuration: 5)

    clock.advance(to: 0.1) // fade should have begun
    #expect(fadeStarts == [0.2])
  }

  @Test @MainActor
  func clear_emitsFadeCancel_andCancelsScheduledFade() {
    let clock = ManualSleepTimerScheduler()
    let manager = SleepTimerManager(scheduler: clock)
    var fadeStarts = 0
    manager.set(seconds: 0.2, fadeDuration: 0.15)
    var cancels = 0
    manager.onFadeStart = { _ in fadeStarts += 1 }
    manager.onFadeCancel = { cancels += 1 }

    #expect(manager.clear())
    #expect(cancels == 1)

    clock.advance(to: 0.3) // well past the would-be deadline
    #expect(fadeStarts == 0)
  }

  @Test @MainActor
  func replacingTimer_emitsFadeCancel() {
    let manager = SleepTimerManager(scheduler: ManualSleepTimerScheduler())
    manager.set(seconds: 60, fadeDuration: 10)
    var cancels = 0
    manager.onFadeCancel = { cancels += 1 }

    manager.set(seconds: 30)

    #expect(cancels == 1)
  }

  @Test @MainActor
  func switchingToEndOfTrack_emitsFadeCancel() {
    let manager = SleepTimerManager(scheduler: ManualSleepTimerScheduler())
    manager.set(seconds: 60, fadeDuration: 10)
    var cancels = 0
    manager.onFadeCancel = { cancels += 1 }

    manager.setToEndOfTrack()

    #expect(cancels == 1)
    #expect(manager.get() == .third(SleepTimerEndOfTrack(sleepWhenPlayedToEnd: true)))
  }

  @Test @MainActor
  func noFadeDuration_schedulesNoFade() {
    let clock = ManualSleepTimerScheduler()
    let manager = SleepTimerManager(scheduler: clock)
    var fadeStarts = 0
    var completes = 0
    manager.onFadeStart = { _ in fadeStarts += 1 }
    manager.onComplete = { completes += 1 }

    manager.set(seconds: 0.1)

    clock.advance(to: 0.2)
    #expect(fadeStarts == 0)
    #expect(completes == 1)
  }
}
