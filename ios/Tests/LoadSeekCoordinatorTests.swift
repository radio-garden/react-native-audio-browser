import AVFoundation
import Testing

@testable import AudioBrowserTestable

// MARK: - Helpers

@MainActor
private final class SeekCompletionSpy: SeekCompletionHandler {
  var calls: [(seconds: Double, didFinish: Bool)] = []
  func handleSeekCompleted(to seconds: Double, didFinish: Bool) {
    calls.append((seconds, didFinish))
  }
}

@MainActor
private func makeCoordinator() -> LoadSeekCoordinator {
  LoadSeekCoordinator()
}

// MARK: - Initial State

@Suite("initial state")
@MainActor
struct InitialStateTests {
  @Test func startsIdle() {
    let c = makeCoordinator()
    guard case .idle = c.state else {
      Issue.record("expected .idle, got \(c.state)")
      return
    }
  }

  @Test func pendingTimeIsNil() {
    let c = makeCoordinator()
    #expect(c.pendingTime == nil)
  }

  @Test func shouldDeferReadyTransitionIsFalse() {
    let c = makeCoordinator()
    #expect(c.shouldDeferReadyTransition == false)
  }
}

// MARK: - Capture

@Suite("capture")
@MainActor
struct CaptureTests {
  @Test func setsStateToPendingSeek() {
    let c = makeCoordinator()
    c.capture(position: 42.0)
    guard case .pendingSeek(let time) = c.state else {
      Issue.record("expected .pendingSeek, got \(c.state)")
      return
    }
    #expect(time == 42.0)
  }

  @Test func updatesPendingTime() {
    let c = makeCoordinator()
    c.capture(position: 10.0)
    #expect(c.pendingTime == 10.0)
  }

  @Test func shouldDeferReadyTransitionBecomesTrue() {
    let c = makeCoordinator()
    c.capture(position: 5.0)
    #expect(c.shouldDeferReadyTransition == true)
  }

  @Test func supersedesPreviousPendingSeek() {
    let c = makeCoordinator()
    c.capture(position: 10.0)
    c.capture(position: 20.0)
    #expect(c.pendingTime == 20.0)
  }

  @Test func overridesSeekInFlight() {
    let c = makeCoordinator()
    let player = AVPlayer()
    c.capture(position: 10.0)
    _ = c.executeIfPending(on: player, delegate: nil)
    // Now in seekInFlight
    guard case .seekInFlight = c.state else {
      Issue.record("expected .seekInFlight, got \(c.state)")
      return
    }
    c.capture(position: 30.0)
    guard case .pendingSeek(let time) = c.state else {
      Issue.record("expected .pendingSeek, got \(c.state)")
      return
    }
    #expect(time == 30.0)
  }
}

// MARK: - executeIfPending

@Suite("executeIfPending")
@MainActor
struct ExecuteIfPendingTests {
  @Test func returnsFalseWhenIdle() {
    let c = makeCoordinator()
    let player = AVPlayer()
    #expect(c.executeIfPending(on: player, delegate: nil) == false)
  }

  @Test func returnsTrueWhenPendingSeek() {
    let c = makeCoordinator()
    let player = AVPlayer()
    c.capture(position: 15.0)
    #expect(c.executeIfPending(on: player, delegate: nil) == true)
  }

  @Test func transitionsToSeekInFlight() {
    let c = makeCoordinator()
    let player = AVPlayer()
    c.capture(position: 15.0)
    _ = c.executeIfPending(on: player, delegate: nil)
    guard case .seekInFlight(let time) = c.state else {
      Issue.record("expected .seekInFlight, got \(c.state)")
      return
    }
    #expect(time == 15.0)
  }

  @Test func preservesTimeValueThroughTransition() {
    let c = makeCoordinator()
    let player = AVPlayer()
    c.capture(position: 99.5)
    _ = c.executeIfPending(on: player, delegate: nil)
    #expect(c.pendingTime == 99.5)
  }

  @Test func returnsFalseWhenSeekInFlight() {
    let c = makeCoordinator()
    let player = AVPlayer()
    c.capture(position: 10.0)
    _ = c.executeIfPending(on: player, delegate: nil)
    // Now in seekInFlight — calling again should return false
    #expect(c.executeIfPending(on: player, delegate: nil) == false)
  }
}

// MARK: - seekDidComplete

@Suite("seekDidComplete")
@MainActor
struct SeekDidCompleteTests {
  @Test func fromSeekInFlight_returnsTrue_resetsToIdle() {
    let c = makeCoordinator()
    let player = AVPlayer()
    c.capture(position: 10.0)
    _ = c.executeIfPending(on: player, delegate: nil)
    #expect(c.seekDidComplete(on: player, delegate: nil) == true)
    guard case .idle = c.state else {
      Issue.record("expected .idle, got \(c.state)")
      return
    }
  }

  @Test func fromPendingSeek_returnsFalse_reExecutes() {
    let c = makeCoordinator()
    let player = AVPlayer()
    // Start a seek
    c.capture(position: 10.0)
    _ = c.executeIfPending(on: player, delegate: nil)
    // Queue a new seek while in-flight
    c.capture(position: 20.0)
    // Complete the first seek — should re-execute
    #expect(c.seekDidComplete(on: player, delegate: nil) == false)
    guard case .seekInFlight(let time) = c.state else {
      Issue.record("expected .seekInFlight, got \(c.state)")
      return
    }
    #expect(time == 20.0)
  }

  @Test func fromIdle_returnsFalse() {
    let c = makeCoordinator()
    let player = AVPlayer()
    #expect(c.seekDidComplete(on: player, delegate: nil) == false)
  }
}

// MARK: - Reset

@Suite("reset")
@MainActor
struct ResetTests {
  @Test func clearsFromPendingSeekToIdle() {
    let c = makeCoordinator()
    c.capture(position: 10.0)
    c.reset()
    guard case .idle = c.state else {
      Issue.record("expected .idle, got \(c.state)")
      return
    }
    #expect(c.pendingTime == nil)
  }

  @Test func clearsFromSeekInFlightToIdle() {
    let c = makeCoordinator()
    let player = AVPlayer()
    c.capture(position: 10.0)
    _ = c.executeIfPending(on: player, delegate: nil)
    c.reset()
    guard case .idle = c.state else {
      Issue.record("expected .idle, got \(c.state)")
      return
    }
  }

  @Test func noopWhenAlreadyIdle() {
    let c = makeCoordinator()
    c.reset()
    guard case .idle = c.state else {
      Issue.record("expected .idle, got \(c.state)")
      return
    }
  }
}

// MARK: - Integration

@Suite("integration")
@MainActor
struct IntegrationTests {
  @Test func fullCycle_capture_execute_complete() {
    let c = makeCoordinator()
    let player = AVPlayer()

    // Capture
    c.capture(position: 30.0)
    #expect(c.shouldDeferReadyTransition == true)

    // Execute
    let executed = c.executeIfPending(on: player, delegate: nil)
    #expect(executed == true)
    guard case .seekInFlight = c.state else {
      Issue.record("expected .seekInFlight")
      return
    }

    // Complete
    let done = c.seekDidComplete(on: player, delegate: nil)
    #expect(done == true)
    guard case .idle = c.state else {
      Issue.record("expected .idle")
      return
    }
    #expect(c.shouldDeferReadyTransition == false)
  }

  @Test func overrideDuringInFlight_capture_execute_capture_complete_reExecute() {
    let c = makeCoordinator()
    let player = AVPlayer()

    // First seek
    c.capture(position: 10.0)
    _ = c.executeIfPending(on: player, delegate: nil)
    guard case .seekInFlight(let t1) = c.state else {
      Issue.record("expected .seekInFlight")
      return
    }
    #expect(t1 == 10.0)

    // Override while in-flight
    c.capture(position: 50.0)
    guard case .pendingSeek(let t2) = c.state else {
      Issue.record("expected .pendingSeek")
      return
    }
    #expect(t2 == 50.0)

    // First seek "completes" — should auto re-execute
    let done = c.seekDidComplete(on: player, delegate: nil)
    #expect(done == false)
    guard case .seekInFlight(let t3) = c.state else {
      Issue.record("expected .seekInFlight after re-execute")
      return
    }
    #expect(t3 == 50.0)

    // Second seek completes
    let done2 = c.seekDidComplete(on: player, delegate: nil)
    #expect(done2 == true)
    guard case .idle = c.state else {
      Issue.record("expected .idle")
      return
    }
  }
}
