import AVFoundation
import Foundation
import Testing

@testable import AudioBrowserTestable

private final class MockNetworkStatus: NetworkStatusProviding, @unchecked Sendable {
  var isOnline: Bool
  init(isOnline: Bool) { self.isOnline = isOnline }
}

/// Timing-based: these run the real backoff (1s, 1.5s, 2.25s, 3.375s), and the budgets are checked
/// before and after each wait, so each budget below sits between two points on that ladder.
@Suite("RetryLoop budgets")
@MainActor
struct RetryLoopBudgetTests {
  /// Returns the mock too: `networkMonitor` is weak, so the test must hold it.
  private func makeManager(
    firstConnectMs: Double,
    maxMs: Double = 120_000,
    online: Bool,
  ) -> (RetryLoop, MockNetworkStatus) {
    let manager = RetryLoop()
    manager.updatePolicy(from: .second(RetryConfig(
      maxRetries: nil,
      maxRetryDurationMs: maxMs,
      firstConnectMaxRetryDurationMs: firstConnectMs,
    )))
    let network = MockNetworkStatus(isOnline: online)
    manager.networkMonitor = network
    return (manager, network)
  }

  @Test func neverPlayedLoad_givesUpAfterFirstConnectBudget() async {
    // Above the first backoff (1s), with ~800ms for sleep overshoot; below the 2.5s at which
    // attempt two's wait ends.
    let (manager, network) = makeManager(firstConnectMs: 1800, online: true)
    _ = network

    #expect(await manager.attemptRetry(startFromCurrentTime: false) == true)
    #expect(await manager.attemptRetry(startFromCurrentTime: false) == false)
  }

  @Test func playedLoad_ignoresTheFirstConnectBudget() async {
    // Below the first backoff: a never-played load would give up on attempt one.
    let (manager, network) = makeManager(firstConnectMs: 500, online: true)
    _ = network
    manager.hasPlayed = { true }

    #expect(await manager.attemptRetry(startFromCurrentTime: false) == true)
    #expect(await manager.attemptRetry(startFromCurrentTime: false) == true)
  }

  @Test func offlineTime_doesNotBurnTheFirstConnectBudget() async {
    // Above the 2.25s wait of the first online attempt (the backoff kept climbing while offline),
    // below the ~5.6s at which the second one's wait ends.
    let (manager, network) = makeManager(firstConnectMs: 4000, online: false)

    #expect(await manager.attemptRetry(startFromCurrentTime: false) == true)
    #expect(await manager.attemptRetry(startFromCurrentTime: false) == true)

    // The first-connect clock starts here, at the first attempt made online.
    network.isOnline = true
    #expect(await manager.attemptRetry(startFromCurrentTime: false) == true)
    #expect(await manager.attemptRetry(startFromCurrentTime: false) == false)
  }
}

// MARK: - handleError

@Suite("RetryLoop.handleError")
@MainActor
struct RetryLoopHandleErrorTests {
  final class Record {
    var retrying: [TrackPlayerError.PlaybackError] = []
    var errors: [TrackPlayerError.PlaybackError] = []
    var reloads: [Bool] = []
  }

  private func makeLoop(givesUp: Bool = false, delay: TimeInterval = 0) -> (RetryLoop, Record) {
    let loop = makeRetryLoop(givesUp: givesUp, delay: delay)
    let record = Record()
    loop.onRetryingError = { record.retrying.append($0) }
    loop.onError = { record.errors.append($0) }
    loop.onRetry = { record.reloads.append($0) }
    return (loop, record)
  }

  @Test func aNonRetryableErrorSurfacesAtOnce() {
    let (loop, record) = makeLoop()
    loop.handleError(URLError(.badURL), context: .playback)
    #expect(record.errors == [.playbackFailed])
    #expect(record.retrying.isEmpty)
    #expect(loop.pendingTask == nil)
  }

  @Test func aDisabledPolicySurfacesAtOnce() {
    let (loop, record) = makeLoop()
    loop.updatePolicy(from: nil)
    loop.handleError(URLError(.notConnectedToInternet), context: .mediaLoad)
    #expect(record.errors == [.notConnectedToInternet])
    #expect(record.retrying.isEmpty)
    #expect(loop.pendingTask == nil)
  }

  @Test func aRetryableErrorReloadsFromTheContextsPosition() async {
    let (loop, record) = makeLoop()
    loop.handleError(URLError(.timedOut), context: .playback)
    #expect(loop.pendingTask != nil)
    await loop.pendingTask?.value
    loop.handleError(URLError(.timedOut), context: .mediaLoad)
    await loop.pendingTask?.value

    #expect(record.reloads == [true, false])
    #expect(record.errors.isEmpty)
    // Session release waits on `pendingTask`, so a finished retry must clear it.
    #expect(loop.pendingTask == nil)
    #expect(loop.attempts == 2)
  }

  @Test func theAdvisoryPrecedesTheRetry() async {
    let (loop, record) = makeLoop()
    loop.handleError(URLError(.cannotConnectToHost), context: .mediaLoad)
    #expect(record.retrying == [.hostUnreachable])
    #expect(record.reloads.isEmpty)
    await loop.pendingTask?.value
    #expect(record.errors.isEmpty)
  }

  @Test func givingUpReportsTheAdvisoryThenTheTerminalError() async {
    let (loop, record) = makeLoop(givesUp: true)
    loop.handleError(URLError(.cannotConnectToHost), context: .mediaLoad)
    await loop.pendingTask?.value
    #expect(record.retrying == [.hostUnreachable])
    #expect(record.errors == [.hostUnreachable])
  }

  @Test func aCancelledRetryStaysSilent() async {
    let (loop, record) = makeLoop(delay: 0.05)
    loop.handleError(URLError(.timedOut), context: .playback)
    let task = loop.pendingTask
    loop.cancel()
    #expect(loop.pendingTask == nil)
    await task?.value
    #expect(record.errors.isEmpty)
    #expect(record.reloads.isEmpty)
  }

  @Test func aNewFailureReplacesTheRetryInFlight() async {
    let (loop, record) = makeLoop(delay: 0.02)
    loop.handleError(URLError(.timedOut), context: .playback)
    let first = loop.pendingTask
    loop.handleError(URLError(.timedOut), context: .playback)
    let second = loop.pendingTask
    await first?.value
    await second?.value
    #expect(record.reloads == [true])
    #expect(record.errors.isEmpty)
  }

  @Test func theHTTPStatusReachesTheReport() async {
    let (loop, record) = makeLoop(givesUp: true)
    loop.handleError(nil, context: .playback, httpStatusCode: 404)
    loop.handleError(URLError(.badURL), context: .playback, httpStatusCode: 503)
    await loop.pendingTask?.value
    #expect(record.errors == [.httpStatus(404), .httpStatus(503)])
  }

  @Test func givingUpOnAnOpaqueFailureReportsTheFallback() async {
    let (loop, record) = makeLoop(givesUp: true)
    loop.handleError(NSError(domain: "CoreMediaErrorDomain", code: -12939), context: .mediaLoad)
    await loop.pendingTask?.value
    #expect(record.errors == [.failedToLoadKeyValue])
  }

  @Test func resetRefillsTheBudgets() async {
    let (loop, _) = makeLoop()
    loop.handleError(URLError(.timedOut), context: .playback)
    await loop.pendingTask?.value
    #expect(loop.attempts == 1)
    loop.reset()
    #expect(loop.attempts == 0)
  }

  @Test func resetDropsTheRetryInFlightSilently() async {
    let (loop, record) = makeLoop(delay: 0.02)
    loop.handleError(URLError(.timedOut), context: .playback)
    let task = loop.pendingTask
    loop.reset()
    #expect(loop.pendingTask == nil)
    await task?.value
    #expect(record.errors.isEmpty)
    #expect(record.reloads.isEmpty)
  }

  /// A loop whose backoff waits until cancelled; the closure says whether it has started waiting.
  private func makeWaitingLoop() -> (RetryLoop, Record, () -> Bool) {
    var waiting = false
    let loop = RetryLoop(sleep: { _ in
      await MainActor.run { waiting = true }
      try await Task.sleep(for: .seconds(60))
    })
    loop.updatePolicy(from: .first(true))
    loop.shouldRetry = { true }
    let record = Record()
    loop.onError = { record.errors.append($0) }
    loop.onRetry = { record.reloads.append($0) }
    return (loop, record, { waiting })
  }

  @Test func aCancelDuringTheBackoffStaysSilent() async {
    let (loop, record, isWaiting) = makeWaitingLoop()
    loop.handleError(URLError(.timedOut), context: .playback)
    let task = loop.pendingTask
    while !isWaiting() {
      await Task.yield()
    }
    loop.cancel()
    await task?.value
    #expect(record.errors.isEmpty)
    #expect(record.reloads.isEmpty)
  }

  @Test func aCancelAsTheBackoffEndsStopsTheReload() async {
    let holder = LoopHolder()
    // The cancel lands as the backoff completes: the sleep returns normally, not by throwing.
    let loop = RetryLoop(sleep: { _ in
      await holder.loop?.cancel()
    })
    holder.loop = loop
    loop.updatePolicy(from: .first(true))
    loop.shouldRetry = { true }
    let record = Record()
    loop.onError = { record.errors.append($0) }
    loop.onRetry = { record.reloads.append($0) }
    loop.handleError(URLError(.timedOut), context: .playback)
    let task = loop.pendingTask
    await task?.value
    #expect(record.reloads.isEmpty)
    #expect(record.errors.isEmpty)
  }
}

@Suite("RetryLoop.handleFailure")
@MainActor
struct RetryLoopHandleFailureTests {
  private final class Sleeps: @unchecked Sendable {
    var seconds: [TimeInterval] = []
  }

  @Test func anImmediateFailureSkipsTheBackoff_butSpendsAnAttempt() async {
    let sleeps = Sleeps()
    let loop = RetryLoop(sleep: { sleeps.seconds.append($0) })
    loop.updatePolicy(from: .first(true))
    var reloads: [Bool] = []
    loop.onRetry = { reloads.append($0) }

    loop.handleFailure(.stall)
    await loop.pendingTask?.value
    loop.handleFailure(.stall, immediately: true)
    await loop.pendingTask?.value

    #expect(sleeps.seconds == [1, 0])
    #expect(reloads == [true, true])
    #expect(loop.attempts == 2)
  }
}

@MainActor
private final class LoopHolder {
  weak var loop: RetryLoop?
}

@Suite("RetryLoop healthy playback")
@MainActor
struct RetryLoopHealthyPlaybackTests {
  private func makeAfterOneRetry() async -> RetryLoop {
    let loop = makeRetryLoop()
    loop.handleError(URLError(.timedOut), context: .playback)
    await loop.pendingTask?.value
    return loop
  }

  @Test func audiblePlaybackForTheWindowRefillsTheBudgets() async {
    let loop = await makeAfterOneRetry()
    #expect(loop.attempts == 1)
    loop.healthyPlaybackDuration = .milliseconds(10)

    loop.playbackBecameAudible()
    await loop.healthyPlaybackTask?.value

    #expect(loop.attempts == 0)
  }

  @Test func stoppingBeforeTheWindowKeepsTheBudgets() async {
    let loop = await makeAfterOneRetry()
    loop.healthyPlaybackDuration = .milliseconds(500)

    loop.playbackBecameAudible()
    let refill = loop.healthyPlaybackTask
    loop.playbackStoppedBeingAudible()
    await refill?.value

    #expect(loop.attempts == 1)
    #expect(loop.healthyPlaybackTask == nil)
  }
}
