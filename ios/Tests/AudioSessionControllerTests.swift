import Foundation
import Testing

@testable import AudioBrowserTestable

final class FakeAudioSession: @unchecked Sendable {
  private let lock = NSLock()
  private var log: [String] = []
  private var activations = 0
  private var refusedActivations: Set<Int> = []
  private var holding = false
  private var waiting = false
  /// Held closed, the queue blocks in the next call until it opens.
  let gate = DispatchSemaphore(value: 0)

  /// Activations, counted from 1, that the system refuses.
  var refused: Set<Int> {
    get { lock.withLock { refusedActivations } }
    set { lock.withLock { refusedActivations = newValue } }
  }

  var holdNextCall: Bool {
    get { lock.withLock { holding } }
    set { lock.withLock { holding = newValue } }
  }

  /// Whether the queue is blocked on `gate`.
  var isWaiting: Bool {
    lock.withLock { waiting }
  }

  var calls: [String] {
    lock.withLock { log }
  }

  func setActive(_ active: Bool) throws {
    wait()
    let refuse = lock.withLock {
      log.append(active ? "activate" : "deactivate")
      if active { activations += 1 }
      return active && refusedActivations.contains(activations)
    }
    if refuse { throw CocoaError(.featureUnsupported) }
  }

  func setCategory(_ name: String = "category") {
    wait()
    lock.withLock { log.append(name) }
  }

  private func wait() {
    guard lock.withLock({ holding }) else { return }
    lock.withLock { waiting = true }
    gate.wait()
    lock.withLock {
      holding = false
      waiting = false
    }
  }
}

@MainActor
final class FakeSessionPlayer: AudioSessionPlayer {
  var releasable = true
  var releaseChecks = 0
  var canReleaseAudioSession: Bool {
    releaseChecks += 1
    return releasable
  }

  var willResumeAfterInterruption = false
  var refusals = 0
  var interruptionsBegan = 0
  var interruptionEnds: [Bool] = []

  func handleInterruptionBegan() { interruptionsBegan += 1 }
  func handleAudioSessionActivationFailed() { refusals += 1 }
  func handleInterruptionEnded(shouldResume: Bool) { interruptionEnds.append(shouldResume) }
  func handleRouteDisconnected() {}
  func mediaServicesWereReset() {}
}

/// What the tests' main-actor closures did, in order: a `@Sendable` closure cannot append to a
/// local array.
@MainActor
final class SessionLog {
  var entries: [String] = []
}

@MainActor
private func makeController(_ session: FakeAudioSession) -> AudioSessionController {
  AudioSessionController(setActive: { try session.setActive($0) }, releaseDelay: .milliseconds(20))
}

/// Yields until `condition` holds, or fails after 2 s.
@MainActor
private func eventually(_ condition: @MainActor () -> Bool, sourceLocation: SourceLocation = #_sourceLocation) async {
  let deadline = Date().addingTimeInterval(2)
  while !condition(), Date() < deadline {
    try? await Task.sleep(for: .milliseconds(5))
  }
  #expect(condition(), sourceLocation: sourceLocation)
}

/// Runs what the activation queue holds, then what it posted to the main actor.
@MainActor
private func drain(_: AudioSessionController) async {
  for _ in 0 ..< 2 {
    await withCheckedContinuation { continuation in
      AudioSessionController.activationQueue.async { Task { @MainActor in continuation.resume() } }
    }
  }
}

// Serialized: the controllers share the session's state, as they do across a JS reload.
@Suite("AudioSessionController", .serialized, .timeLimit(.minutes(1)))
@MainActor
struct AudioSessionControllerTests {
  @Test func aRefusalIsReportedToThePlayer() async {
    let session = FakeAudioSession()
    session.refused = [1]
    let controller = makeController(session)
    let player = FakeSessionPlayer()
    controller.player = player

    controller.activate()

    await eventually { player.refusals == 1 }
  }

  @Test func aRefusalOvertakenByALaterActivationIsIgnored() async {
    let session = FakeAudioSession()
    session.refused = [1]
    let controller = makeController(session)
    let player = FakeSessionPlayer()
    controller.player = player

    controller.activate()
    controller.activate()

    await eventually { session.calls == ["activate", "activate"] }
    await drain(controller)
    #expect(player.refusals == 0)
  }

  @Test func aRefusalOvertakenByAnInterruptionIsIgnored() async {
    let session = FakeAudioSession()
    session.refused = [1]
    let controller = makeController(session)
    let player = FakeSessionPlayer()
    controller.player = player

    controller.activate()
    controller.interruptionBegan()

    await eventually { session.calls == ["activate"] }
    await drain(controller)
    #expect(player.refusals == 0)
    #expect(player.interruptionsBegan == 1)
  }

  @Test func aReloadsReleaseIsJudgedAgainstTheNewPlayer() async {
    let session = FakeAudioSession()
    var old: AudioSessionController? = makeController(session)
    let oldPlayer = FakeSessionPlayer()
    old?.player = oldPlayer
    old?.scheduleRelease()

    old?.retire()
    old = nil
    let new = makeController(session)
    let newPlayer = FakeSessionPlayer()
    newPlayer.releasable = false
    new.player = newPlayer

    await eventually { newPlayer.releaseChecks == 1 }
    await drain(new)
    #expect(session.calls == [])

    newPlayer.releasable = true
    new.scheduleRelease()
    await eventually { session.calls == ["deactivate"] }
  }

  @Test func aReleaseOwedByADroppedControllerStillRuns() async {
    let session = FakeAudioSession()
    var old: AudioSessionController? = makeController(session)
    let player = FakeSessionPlayer()
    old?.player = player
    old?.scheduleRelease()
    old?.retire()
    old = nil

    await eventually { session.calls == ["deactivate"] }
  }

  @Test func aCategoryQueuedBeforeAReloadIsSkipped() async {
    let session = FakeAudioSession()
    let old = makeController(session)
    session.holdNextCall = true
    old.activate()
    let configured = Task { await old.configure { session.setCategory() } }
    await Task.yield()

    old.retire()
    session.gate.signal()
    await configured.value

    #expect(session.calls == ["activate"])
  }

  @Test func configureReturnsOnceTheCategoryIsSet() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    session.holdNextCall = true
    let log = SessionLog()
    let call = Task {
      await controller.configure { session.setCategory() }
      log.entries.append("returned")
    }

    await eventually { session.isWaiting }
    #expect(log.entries == [])
    session.gate.signal()
    await call.value
    #expect(session.calls == ["category"])
  }

  @Test func aCategoryLandsBeforeTheActivationAfterIt() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    session.holdNextCall = true
    let configured = Task { await controller.configure { session.setCategory() } }
    await eventually { session.isWaiting }
    controller.activate()

    session.gate.signal()
    await configured.value
    await eventually { session.calls == ["category", "activate"] }
  }

  @Test func anInterruptionEndReactivatesBeforeItResumes() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    let player = FakeSessionPlayer()
    player.willResumeAfterInterruption = true
    controller.player = player
    session.holdNextCall = true

    controller.interruptionEnded(shouldResume: true)
    await eventually { session.isWaiting }
    #expect(player.interruptionEnds == [])
    session.gate.signal()

    await eventually { player.interruptionEnds == [true] }
    #expect(session.calls == ["activate"])
  }
}
