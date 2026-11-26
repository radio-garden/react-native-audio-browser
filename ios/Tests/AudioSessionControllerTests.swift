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
  /// As the real player's: a held resume keeps the session.
  var canReleaseAudioSession: Bool {
    releaseChecks += 1
    return releasable && !willResumeAfterInterruption
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

/// Waits past `makeController`'s 20 ms release delay, then drains the activation queue.
@MainActor
private func outlastTheReleaseDelay(_ controller: AudioSessionController) async {
  try? await Task.sleep(for: .milliseconds(100))
  await drain(controller)
}

/// Runs what the activation queue holds, then what it posted to the main actor.
@MainActor
private func drain(_ controller: AudioSessionController) async {
  for _ in 0 ..< 2 {
    await withCheckedContinuation { continuation in
      controller.activationQueue.async { Task { @MainActor in continuation.resume() } }
    }
  }
}

@Suite("AudioSessionController", .timeLimit(.minutes(1)))
@MainActor
struct AudioSessionControllerTests {
  @Test func aRefusalIsReportedToThePlayer() async {
    let session = FakeAudioSession()
    session.refused = [1]
    let controller = makeController(session)
    let player = FakeSessionPlayer()
    controller.player = player

    controller.activate(for: player)

    await eventually { player.refusals == 1 }
  }

  @Test func aRefusalOvertakenByALaterActivationIsIgnored() async {
    let session = FakeAudioSession()
    session.refused = [1]
    let controller = makeController(session)
    let player = FakeSessionPlayer()
    controller.player = player

    controller.activate(for: player)
    controller.activate(for: player)

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

    controller.activate(for: player)
    controller.interruptionBegan()

    await eventually { session.calls == ["activate"] }
    await drain(controller)
    #expect(player.refusals == 0)
    #expect(player.interruptionsBegan == 1)
  }

  @Test func aReloadsReleaseIsJudgedAgainstTheNewPlayer() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    let oldPlayer = FakeSessionPlayer()
    controller.player = oldPlayer
    controller.scheduleRelease()

    controller.detach(oldPlayer)
    let newPlayer = FakeSessionPlayer()
    newPlayer.releasable = false
    controller.player = newPlayer

    await eventually { newPlayer.releaseChecks == 1 }
    await drain(controller)
    #expect(session.calls == [])

    newPlayer.releasable = true
    controller.scheduleRelease()
    await eventually { session.calls == ["deactivate"] }
  }

  @Test func aReleaseScheduledBeforeAReloadStillRuns() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    let player = FakeSessionPlayer()
    player.releasable = false
    controller.player = player
    controller.scheduleRelease()

    controller.detach(player)

    await eventually { session.calls == ["deactivate"] }
  }

  @Test func detachKeepsAPlayerThatReplacedTheDetachedOne() {
    let controller = makeController(FakeAudioSession())
    let old = FakeSessionPlayer()
    let new = FakeSessionPlayer()
    controller.player = new

    controller.detach(old)

    #expect(controller.player === new)
  }

  @Test func theLatestCategoryLandsLast() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    session.holdNextCall = true
    let first = Task { await controller.configure { session.setCategory("first") } }
    await eventually { session.isWaiting }
    let second = Task { await controller.configure { session.setCategory("second") } }

    session.gate.signal()
    await first.value
    await second.value

    #expect(session.calls == ["first", "second"])
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
    let player = FakeSessionPlayer()
    controller.player = player
    session.holdNextCall = true
    let configured = Task { await controller.configure { session.setCategory() } }
    await eventually { session.isWaiting }
    controller.activate(for: player)

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

  @Test func aCallEndingAfterAReloadLeavesTheSessionAlone() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    let oldPlayer = FakeSessionPlayer()
    oldPlayer.willResumeAfterInterruption = true
    controller.player = oldPlayer
    controller.interruptionBegan()

    controller.detach(oldPlayer)
    controller.interruptionEnded(shouldResume: true)

    await drain(controller)
    #expect(session.calls == [])
    #expect(oldPlayer.interruptionEnds == [])
  }

  @Test func aReloadDuringACallsReactivationReleasesTheSession() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    let player = FakeSessionPlayer()
    player.willResumeAfterInterruption = true
    controller.player = player
    session.holdNextCall = true

    controller.interruptionEnded(shouldResume: true)
    await eventually { session.isWaiting }
    controller.detach(player)
    session.gate.signal()

    await eventually { session.calls == ["activate", "deactivate"] }
    #expect(player.interruptionEnds == [])
  }

  @Test func aCallEndingAfterTheNewSetupIsTheNewPlayers() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    let oldPlayer = FakeSessionPlayer()
    oldPlayer.willResumeAfterInterruption = true
    controller.player = oldPlayer
    controller.interruptionBegan()

    controller.detach(oldPlayer)
    let newPlayer = FakeSessionPlayer()
    controller.player = newPlayer
    controller.interruptionEnded(shouldResume: true)

    await drain(controller)
    #expect(session.calls == [])
    #expect(oldPlayer.interruptionEnds == [])
    #expect(newPlayer.interruptionEnds == [true])
  }

  @Test func aResumeOvertakenByAnotherInterruptionWaitsForItsEnd() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    let player = FakeSessionPlayer()
    player.willResumeAfterInterruption = true
    controller.player = player
    session.holdNextCall = true

    controller.interruptionEnded(shouldResume: true)
    await eventually { session.isWaiting }
    controller.interruptionBegan()
    session.gate.signal()
    await drain(controller)
    #expect(player.interruptionEnds == [])

    controller.interruptionEnded(shouldResume: true)

    await eventually { player.interruptionEnds == [true] }
    #expect(session.calls == ["activate", "activate"])
  }

  @Test func aSkippedResumeStillReleasesASessionNothingHolds() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    let player = FakeSessionPlayer()
    player.willResumeAfterInterruption = true
    controller.player = player
    session.holdNextCall = true

    controller.interruptionEnded(shouldResume: true)
    await eventually { session.isWaiting }
    controller.interruptionBegan()
    // An unplug during the second interruption drops the resume the player kept.
    player.willResumeAfterInterruption = false
    session.gate.signal()

    await eventually { session.calls == ["activate", "deactivate"] }
    #expect(player.interruptionEnds == [])
  }

  @Test func anActivationsFollowUpRunsThoughAnInterruptionOvertookIt() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    let player = FakeSessionPlayer()
    controller.player = player
    let followUps = SessionLog()
    session.holdNextCall = true

    controller.activate(for: player) { followUps.entries.append("first") }
    await eventually { session.isWaiting }
    controller.interruptionBegan()
    controller.activate(for: player) { followUps.entries.append("second") }
    session.gate.signal()

    await eventually { followUps.entries == ["first", "second"] }
  }

  @Test func aDetachedPlayersActivationKeepsTheNewPlayersRefusal() async {
    let session = FakeAudioSession()
    session.refused = [1]
    let controller = makeController(session)
    let oldPlayer = FakeSessionPlayer()
    controller.player = oldPlayer
    controller.detach(oldPlayer)
    let newPlayer = FakeSessionPlayer()
    controller.player = newPlayer

    controller.activate(for: newPlayer)
    controller.activate(for: oldPlayer)

    await eventually { newPlayer.refusals == 1 }
    await drain(controller)
    #expect(session.calls == ["activate"])
  }

  @Test func aDetachedPlayersCancelKeepsTheNewPlayersRelease() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    let oldPlayer = FakeSessionPlayer()
    controller.player = oldPlayer
    controller.detach(oldPlayer)
    let newPlayer = FakeSessionPlayer()
    controller.player = newPlayer

    controller.scheduleRelease()
    controller.cancelRelease(for: oldPlayer)

    await eventually { session.calls == ["deactivate"] }
  }

  @Test func theServedPlayersCancelDropsThePendingRelease() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    let player = FakeSessionPlayer()
    controller.player = player

    controller.scheduleRelease()
    controller.cancelRelease(for: player)

    await outlastTheReleaseDelay(controller)
    #expect(session.calls == [])
    #expect(player.releaseChecks == 0)
  }

  @Test func theServedPlayersActivationDropsThePendingRelease() async {
    let session = FakeAudioSession()
    let controller = makeController(session)
    let player = FakeSessionPlayer()
    controller.player = player

    controller.scheduleRelease()
    controller.activate(for: player)

    await outlastTheReleaseDelay(controller)
    #expect(session.calls == ["activate"])
    #expect(player.releaseChecks == 0)
  }

  @Test func aRefusalOfADetachedPlayersActivationIsNotTheNewPlayers() async {
    let session = FakeAudioSession()
    session.refused = [1]
    let controller = makeController(session)
    let oldPlayer = FakeSessionPlayer()
    controller.player = oldPlayer
    session.holdNextCall = true

    controller.activate(for: oldPlayer)
    await eventually { session.isWaiting }
    controller.detach(oldPlayer)
    let newPlayer = FakeSessionPlayer()
    controller.player = newPlayer
    session.gate.signal()

    await eventually { session.calls == ["activate"] }
    await drain(controller)
    #expect(newPlayer.refusals == 0)
    #expect(oldPlayer.refusals == 0)
  }
}
