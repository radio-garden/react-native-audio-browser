import Foundation
import Testing

@testable import AudioBrowserTestable

final class FakeAudioSession: @unchecked Sendable {
  private let lock = NSLock()
  private var log: [String] = []
  private var activations = 0
  /// Activations, counted from 1, that the system refuses.
  var refused: Set<Int> = []
  /// Held closed, the queue blocks in the next call until it opens.
  let gate = DispatchSemaphore(value: 0)
  var holdNextCall = false

  var calls: [String] {
    lock.withLock { log }
  }

  func setActive(_ active: Bool) throws {
    wait()
    let refuse = lock.withLock {
      log.append(active ? "activate" : "deactivate")
      if active { activations += 1 }
      return active && refused.contains(activations)
    }
    if refuse { throw CocoaError(.featureUnsupported) }
  }

  func setCategory() {
    wait()
    lock.withLock { log.append("category") }
  }

  private func wait() {
    if lock.withLock({ holdNextCall }) {
      gate.wait()
      lock.withLock { holdNextCall = false }
    }
  }
}

@MainActor
final class FakeSessionPlayer: AudioSessionPlayer {
  var canReleaseAudioSession = true
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

@MainActor
private func settle() async {
  try? await Task.sleep(for: .milliseconds(100))
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
    await settle()
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
    await settle()
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
    newPlayer.canReleaseAudioSession = false
    new.player = newPlayer

    await settle()
    #expect(session.calls == [])

    newPlayer.canReleaseAudioSession = true
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
    await settle()

    old.retire()
    session.gate.signal()
    await configured.value

    #expect(session.calls == ["activate"])
  }

  @Test func configureReturnsOnceTheCategoryIsSet() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    session.holdNextCall = true
    let configured = Task { await controller.configure { session.setCategory() } }

    await settle()
    #expect(session.calls == [])
    session.gate.signal()
    await configured.value
    #expect(session.calls == ["category"])
  }

  @Test func aCategoryLandsBeforeTheActivationAfterIt() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    session.holdNextCall = true
    let configured = Task { await controller.configure { session.setCategory() } }
    await settle()
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

    controller.interruptionEnded(shouldResume: true)

    await eventually { player.interruptionEnds == [true] }
    #expect(session.calls == ["activate"])
  }
}
