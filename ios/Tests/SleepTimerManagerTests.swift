@testable import AudioBrowserTestable
import Foundation
import Testing

// Timing tests drive a ManualSleepTimerScheduler rather than the main queue:
// every deadline below is virtual, so nothing races the real clock.

@MainActor
private final class Volume {
  var level: Float = 0.8
}

@MainActor
private func makeManager() -> (SleepTimerManager, ManualSleepTimerScheduler, Volume, VolumeFader) {
  let clock = ManualSleepTimerScheduler()
  let volume = Volume()
  let fader = VolumeFader(getVolume: { volume.level }, setVolume: { volume.level = $0 })
  return (SleepTimerManager(fader: fader, scheduler: clock), clock, volume, fader)
}

@Suite("SleepTimerManager - fade scheduling")
struct SleepTimerManagerTests {
  @Test @MainActor
  func fadeStartsBeforeDeadline_completionRestoresAfterHalting() {
    let (manager, clock, volume, fader) = makeManager()
    var completes = 0
    var volumeAtHalt: Float?
    manager.onComplete = {
      completes += 1
      volumeAtHalt = volume.level
    }
    manager.set(seconds: 0.3, fadeDuration: 0.15)

    clock.advance(to: 0.05) // before the fade window
    #expect(!manager.isFading)
    #expect(manager.get() != nil)

    clock.advance(to: 0.22) // inside the fade window
    #expect(manager.isFading)
    #expect(fader.duration == 0.15)
    #expect(fader.originalVolume == 0.8)
    #expect(completes == 0)
    volume.level = 0.1 // mid-fade

    clock.advance(to: 0.4) // past the deadline
    #expect(completes == 1)
    #expect(volumeAtHalt == 0.1) // halted before the restore
    #expect(volume.level == 0.8)
    #expect(!manager.isFading)
    #expect(manager.get() == nil)
  }

  @Test @MainActor
  func fadeLongerThanTimer_clampsAndStartsImmediately() {
    let (manager, clock, _, fader) = makeManager()

    manager.set(seconds: 0.2, fadeDuration: 5)

    clock.advance(to: 0.1) // fade should have begun
    #expect(manager.isFading)
    #expect(fader.duration == 0.2)
  }

  @Test @MainActor
  func clear_restoresVolume_andCancelsScheduledFade() {
    let (manager, clock, volume, _) = makeManager()
    manager.set(seconds: 0.2, fadeDuration: 0.15)
    clock.advance(to: 0.1)
    volume.level = 0.1 // mid-fade

    #expect(manager.clear())
    #expect(volume.level == 0.8)
    #expect(!manager.isFading)

    clock.advance(to: 0.3) // well past the would-be deadline
    #expect(!manager.isFading)
  }

  @Test @MainActor
  func clearBeforeFade_cancelsScheduledFade() {
    let (manager, clock, _, _) = makeManager()
    manager.set(seconds: 0.2, fadeDuration: 0.15)

    #expect(manager.clear())

    clock.advance(to: 0.3)
    #expect(!manager.isFading)
  }

  @Test @MainActor
  func replacingTimer_cancelsTheFade() {
    let (manager, clock, volume, _) = makeManager()
    manager.set(seconds: 60, fadeDuration: 10)
    clock.advance(to: 55)
    volume.level = 0.1 // mid-fade

    manager.set(seconds: 30)

    #expect(!manager.isFading)
    #expect(volume.level == 0.8)
  }

  @Test @MainActor
  func switchingToEndOfTrack_cancelsTheFade() {
    let (manager, clock, volume, _) = makeManager()
    manager.set(seconds: 60, fadeDuration: 10)
    clock.advance(to: 55)
    volume.level = 0.1 // mid-fade

    manager.setToEndOfTrack()

    #expect(!manager.isFading)
    #expect(volume.level == 0.8)
    #expect(manager.get() == .third(SleepTimerEndOfTrack(sleepWhenPlayedToEnd: true)))
  }

  @Test @MainActor
  func noFadeDuration_schedulesNoFade() {
    let (manager, clock, volume, _) = makeManager()
    var completes = 0
    manager.onComplete = { completes += 1 }

    manager.set(seconds: 0.1)

    clock.advance(to: 0.05)
    #expect(!manager.isFading)
    clock.advance(to: 0.2)
    #expect(completes == 1)
    #expect(volume.level == 0.8)
  }
}
