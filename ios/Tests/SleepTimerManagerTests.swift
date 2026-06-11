@testable import AudioBrowserTestable
import Foundation
import Testing

// Timing tests use generous margins (50ms+) around DispatchQueue scheduling
// slop. While an @MainActor test awaits Task.sleep, the main queue is free to
// run the manager's scheduled jobs.

@Suite("SleepTimerManager - fade scheduling")
struct SleepTimerManagerTests {
  @Test @MainActor
  func fadeFiresBeforeDeadline_completionDoesNotEmitFadeCancel() async throws {
    let manager = SleepTimerManager()
    var fadeStarts: [TimeInterval] = []
    var completes = 0
    var cancels = 0
    manager.onFadeStart = { fadeStarts.append($0) }
    manager.onComplete = { completes += 1 }
    manager.set(seconds: 0.3, fadeDuration: 0.15)
    // Attach after set() — replacing a timer emits a defensive onFadeCancel.
    manager.onFadeCancel = { cancels += 1 }

    try await Task.sleep(nanoseconds: 50_000_000) // 0.05s — before the fade window
    #expect(fadeStarts.isEmpty)
    #expect(manager.get() != nil)

    try await Task.sleep(nanoseconds: 170_000_000) // ~0.22s — inside the fade window
    #expect(fadeStarts == [0.15])
    #expect(completes == 0)

    try await Task.sleep(nanoseconds: 180_000_000) // ~0.40s — past the deadline
    #expect(completes == 1)
    #expect(cancels == 0)
    #expect(manager.get() == nil)
  }

  @Test @MainActor
  func fadeLongerThanTimer_clampsAndStartsImmediately() async throws {
    let manager = SleepTimerManager()
    var fadeStarts: [TimeInterval] = []
    manager.onFadeStart = { fadeStarts.append($0) }

    manager.set(seconds: 0.2, fadeDuration: 5)

    try await Task.sleep(nanoseconds: 100_000_000) // 0.1s — fade should have begun
    #expect(fadeStarts == [0.2])
  }

  @Test @MainActor
  func clear_emitsFadeCancel_andCancelsScheduledFade() async throws {
    let manager = SleepTimerManager()
    var fadeStarts = 0
    manager.set(seconds: 0.2, fadeDuration: 0.15)
    var cancels = 0
    manager.onFadeStart = { _ in fadeStarts += 1 }
    manager.onFadeCancel = { cancels += 1 }

    #expect(manager.clear())
    #expect(cancels == 1)

    try await Task.sleep(nanoseconds: 300_000_000) // well past the would-be deadline
    #expect(fadeStarts == 0)
  }

  @Test @MainActor
  func replacingTimer_emitsFadeCancel() {
    let manager = SleepTimerManager()
    manager.set(seconds: 60, fadeDuration: 10)
    var cancels = 0
    manager.onFadeCancel = { cancels += 1 }

    manager.set(seconds: 30)

    #expect(cancels == 1)
  }

  @Test @MainActor
  func switchingToEndOfTrack_emitsFadeCancel() {
    let manager = SleepTimerManager()
    manager.set(seconds: 60, fadeDuration: 10)
    var cancels = 0
    manager.onFadeCancel = { cancels += 1 }

    manager.setToEndOfTrack()

    #expect(cancels == 1)
    #expect(manager.get() == .third(SleepTimerEndOfTrack(sleepWhenPlayedToEnd: true)))
  }

  @Test @MainActor
  func noFadeDuration_schedulesNoFade() async throws {
    let manager = SleepTimerManager()
    var fadeStarts = 0
    var completes = 0
    manager.onFadeStart = { _ in fadeStarts += 1 }
    manager.onComplete = { completes += 1 }

    manager.set(seconds: 0.1)

    try await Task.sleep(nanoseconds: 200_000_000)
    #expect(fadeStarts == 0)
    #expect(completes == 1)
  }
}
