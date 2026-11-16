@testable import AudioBrowserTestable
import Foundation
import Testing

@MainActor
private final class Volume {
  var level: Float = 0.8
}

@MainActor
private func makeManager() -> (SleepTimerManager, ManualSleepTimerScheduler, Volume, VolumeFader) {
  let clock = ManualSleepTimerScheduler()
  let volume = Volume()
  let fader = VolumeFader(getVolume: { volume.level }, setVolume: { volume.level = $0 }, scheduler: clock)
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

    clock.advance(to: 0.05) // before the fade window opens at 0.15
    #expect(!manager.isFading)
    #expect(manager.get() != nil)

    clock.advance(to: 0.22) // inside the fade window
    #expect(manager.isFading)
    #expect(fader.duration == 0.15)
    #expect(fader.originalVolume == 0.8)
    #expect(volume.level < 0.8)
    #expect(completes == 0)

    clock.advance(to: 0.4)
    #expect(completes == 1)
    #expect(volumeAtHalt.map { $0 < 0.8 } == true) // halted before the restore
    #expect(volume.level == 0.8)
    #expect(!manager.isFading)
    #expect(manager.get() == nil)
  }

  @Test @MainActor
  func lateFadeStart_joinsTheRampWhereItWouldBe() {
    let (manager, clock, volume, _) = makeManager()
    manager.set(seconds: 20, fadeDuration: 10)

    clock.advance(to: 15) // the fade, due at 10, runs 5 s late

    #expect(volume.level == 0.8 * VolumeFader.gain(at: 0.5))
  }

  @Test @MainActor
  func fadeFallsByEqualDecibelsEachStep() {
    let (manager, clock, volume, _) = makeManager()
    manager.set(seconds: 20, fadeDuration: 10)

    var decibels: [Float] = []
    for second in 10 ... 19 {
      clock.advance(to: TimeInterval(second))
      decibels.append(20 * log10(volume.level / 0.8))
    }

    let drops = zip(decibels, decibels.dropFirst()).map { $0 - $1 }
    for drop in drops {
      #expect(abs(drop + VolumeFader.floorDecibels / 10) < 0.01)
    }
    #expect(abs(decibels.last! - 0.9 * VolumeFader.floorDecibels) < 0.01)
  }

  @Test @MainActor
  func fadeLongerThanTimer_clampsAndStartsImmediately() {
    let (manager, clock, _, fader) = makeManager()

    manager.set(seconds: 0.2, fadeDuration: 5)

    clock.advance(to: 0.1)
    #expect(manager.isFading)
    #expect(fader.duration == 0.2)
  }

  @Test @MainActor
  func clear_restoresVolume_andCancelsScheduledFade() {
    let (manager, clock, volume, _) = makeManager()
    manager.set(seconds: 0.2, fadeDuration: 0.15)
    clock.advance(to: 0.1)
    volume.level = 0.1 // stands in for the fade lowering it

    #expect(manager.clear())
    #expect(volume.level == 0.8)
    #expect(!manager.isFading)

    clock.advance(to: 0.3)
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
    volume.level = 0.1 // stands in for the fade lowering it

    manager.set(seconds: 30)

    #expect(!manager.isFading)
    #expect(volume.level == 0.8)
  }

  @Test @MainActor
  func switchingToEndOfTrack_cancelsTheFade() {
    let (manager, clock, volume, _) = makeManager()
    manager.set(seconds: 60, fadeDuration: 10)
    clock.advance(to: 55)
    volume.level = 0.1 // stands in for the fade lowering it

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

@MainActor
private final class FakeTimelineFader: TimelineFading {
  var available = true
  var placed: [(secondsAhead: TimeInterval, duration: TimeInterval)] = []
  var cancels = 0

  func startTimelineFade(endingIn secondsAhead: TimeInterval, duration: TimeInterval) -> Bool {
    guard available else { return false }
    placed.append((secondsAhead, duration))
    return true
  }

  func cancelTimelineFade() {
    cancels += 1
  }
}

@MainActor
private func makeTimelineManager() -> (
  SleepTimerManager, ManualSleepTimerScheduler, Volume, FakeTimelineFader,
) {
  let clock = ManualSleepTimerScheduler()
  let volume = Volume()
  let timeline = FakeTimelineFader()
  let fader = VolumeFader(getVolume: { volume.level }, setVolume: { volume.level = $0 }, scheduler: clock)
  let manager = SleepTimerManager(fader: fader, timelineFader: timeline, scheduler: clock)
  return (manager, clock, volume, timeline)
}

@Suite("SleepTimerManager - timeline fade")
struct SleepTimerTimelineFadeTests {
  @Test @MainActor
  func placedAheadOfTheFadeWindow_endingAtTheDeadline() {
    let (manager, clock, _, timeline) = makeTimelineManager()
    manager.set(seconds: 300, fadeDuration: 30)

    clock.advance(to: 209)
    #expect(timeline.placed.isEmpty)

    clock.advance(to: 210) // 30 s fade + 60 s lead before the deadline
    #expect(timeline.placed.count == 1)
    #expect(timeline.placed.first?.secondsAhead == 90)
    #expect(timeline.placed.first?.duration == 30)
  }

  @Test @MainActor
  func placed_leavesTheVolumeAlone_andCountsAsFading() {
    let (manager, clock, volume, timeline) = makeTimelineManager()
    manager.set(seconds: 300, fadeDuration: 30)

    clock.advance(to: 285)

    #expect(timeline.placed.count == 1)
    #expect(volume.level == 0.8)
    #expect(manager.isFading)
  }

  @Test @MainActor
  func completion_haltsThenRemovesTheFade() {
    let (manager, clock, _, timeline) = makeTimelineManager()
    var cancelsAtHalt: Int?
    manager.onComplete = { cancelsAtHalt = timeline.cancels }
    manager.set(seconds: 300, fadeDuration: 30)

    clock.advance(to: 300)

    #expect(cancelsAtHalt == 0)
    #expect(timeline.cancels == 1)
    #expect(!manager.isFading)
  }

  @Test @MainActor
  func clear_removesThePlacedFade() {
    let (manager, clock, _, timeline) = makeTimelineManager()
    manager.set(seconds: 300, fadeDuration: 30)
    clock.advance(to: 250)

    manager.clear()

    #expect(timeline.cancels == 1)
    #expect(!manager.isFading)
  }

  @Test @MainActor
  func unavailable_fadesTheVolumeInstead() {
    let (manager, clock, volume, timeline) = makeTimelineManager()
    timeline.available = false
    manager.set(seconds: 300, fadeDuration: 30)

    clock.advance(to: 285)

    #expect(volume.level < 0.8)
    clock.advance(to: 300)
    #expect(timeline.cancels == 0)
  }

  @Test @MainActor
  func shortTimer_placesTheFadeAtOnce() {
    let (manager, clock, _, timeline) = makeTimelineManager()
    manager.set(seconds: 60, fadeDuration: 30)

    clock.advance(to: 0)

    #expect(timeline.placed.first?.secondsAhead == 60)
  }
}
