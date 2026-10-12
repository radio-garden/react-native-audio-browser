import AVFoundation
import Foundation
import Testing

@testable import AudioBrowserTestable

private final class MockNetworkStatus: NetworkStatusProviding, @unchecked Sendable {
  var isOnline: Bool
  init(isOnline: Bool) { self.isOnline = isOnline }
}

/// The two duration budgets: a never-played load gives up fast while online; a
/// load that produced audio — or a device that is offline — keeps the full
/// recovery budget. Timing-based: budgets are configured in single-digit
/// milliseconds so one executed backoff (~1s) exhausts them.
@Suite("RetryLoop budgets")
@MainActor
struct RetryLoopBudgetTests {
  /// Returns the mock too — `networkMonitor` is weak, the test must keep it alive.
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

  /// A dead-on-arrival stream: the first attempt runs, and the budget refuses
  /// a later one. Budgets are checked before AND after each backoff wait, so
  /// the test budget must exceed the first backoff (~1s) for attempt one to
  /// execute, and be exceeded by the cumulative ~2.5s before attempt two ends.
  /// 1800ms sits ~800ms clear of the first backoff (sleep overshoot on a loaded
  /// machine only widens the passing margins, never shrinks them into failure).
  @Test func neverPlayedLoad_givesUpAfterFirstConnectBudget() async {
    let (manager, network) = makeManager(firstConnectMs: 1800, online: true)
    _ = network

    #expect(await manager.attemptRetry(startFromCurrentTime: false) == true)
    #expect(await manager.attemptRetry(startFromCurrentTime: false) == false)
  }

  /// The same failure timeline on a load that has produced audio keeps
  /// retrying: only the recovery budget applies.
  @Test func playedLoad_ignoresTheFirstConnectBudget() async {
    let (manager, network) = makeManager(firstConnectMs: 500, online: true)
    _ = network
    manager.hasPlayed = { true }

    #expect(await manager.attemptRetry(startFromCurrentTime: false) == true)
    #expect(await manager.attemptRetry(startFromCurrentTime: false) == true)
  }

  /// Offline failures must not burn the first-connect budget (a station tapped
  /// in a tunnel parks for connectivity); the budget's clock starts at the
  /// first failure observed online, so a dead station still gets a fast
  /// verdict after restoration.
  @Test func offlineTime_doesNotBurnTheFirstConnectBudget() async {
    // 4s: well above the ~2.3s backoff of the first online attempt (the ladder
    // kept climbing while offline), well below the ~5.6s cumulative after it.
    let (manager, network) = makeManager(firstConnectMs: 4000, online: false)

    // Offline: the short budget never engages, attempts keep running.
    #expect(await manager.attemptRetry(startFromCurrentTime: false) == true)
    #expect(await manager.attemptRetry(startFromCurrentTime: false) == true)

    // Connectivity returns: the short clock starts now — one more attempt
    // runs, then the budget is spent.
    network.isOnline = true
    #expect(await manager.attemptRetry(startFromCurrentTime: false) == true)
    #expect(await manager.attemptRetry(startFromCurrentTime: false) == false)
  }
}

// MARK: - handleError

@Suite("RetryLoop.handleError")
@MainActor
struct RetryLoopHandleErrorTests {
  /// What the loop reported and did, in order.
  final class Record {
    var retrying: [TrackPlayerError.PlaybackError] = []
    var errors: [TrackPlayerError.PlaybackError] = []
    var reloads: [Bool] = []
  }

  private func makeLoop(retries: Bool = true, delay: TimeInterval = 0) -> (RetryLoop, Record) {
    let loop = makeRetryLoop(retries: retries, delay: delay)
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

  /// A disabled policy makes every error terminal: no advisory, no retry task, even for a
  /// retryable failure, which is still classified.
  @Test func aDisabledPolicySurfacesAtOnce() {
    let (loop, record) = makeLoop()
    loop.updatePolicy(from: nil)
    loop.handleError(URLError(.notConnectedToInternet), context: .mediaLoad)
    #expect(record.errors == [.notConnectedToInternet])
    #expect(record.retrying.isEmpty)
    #expect(loop.pendingTask == nil)
  }

  /// Also pins that a finished retry clears its handle: session release waits on it.
  @Test func aRetryableErrorReloadsFromTheContextsPosition() async {
    let (loop, record) = makeLoop()
    loop.handleError(URLError(.timedOut), context: .playback)
    #expect(loop.pendingTask != nil)
    await loop.pendingTask?.value
    loop.handleError(URLError(.timedOut), context: .mediaLoad)
    await loop.pendingTask?.value

    #expect(record.reloads == [true, false])
    #expect(record.errors.isEmpty)
    #expect(loop.pendingTask == nil)
    #expect(loop.attempts == 2)
  }

  /// The advisory goes out before the backoff, carrying the classification a terminal report
  /// would.
  @Test func theAdvisoryPrecedesTheRetry() async {
    let (loop, record) = makeLoop()
    loop.handleError(URLError(.cannotConnectToHost), context: .mediaLoad)
    #expect(record.retrying == [.hostUnreachable])
    #expect(record.reloads.isEmpty)
    await loop.pendingTask?.value
    #expect(record.errors.isEmpty)
  }

  @Test func givingUpReportsTheAdvisoryThenTheTerminalError() async {
    let (loop, record) = makeLoop(retries: false)
    loop.handleError(URLError(.cannotConnectToHost), context: .mediaLoad)
    await loop.pendingTask?.value
    #expect(record.retrying == [.hostUnreachable])
    #expect(record.errors == [.hostUnreachable])
  }

  /// A cancelled retry stays silent: a track change or a stop cancels it, and surfacing its
  /// failure would flip the new track or the stop to .error.
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

  /// A second failure replaces the retry in flight, which neither reports nor reloads.
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
    let (loop, record) = makeLoop(retries: false)
    loop.handleError(nil, context: .playback, httpStatusCode: 404)
    loop.handleError(URLError(.badURL), context: .playback, httpStatusCode: 503)
    await loop.pendingTask?.value
    #expect(record.errors == [.httpStatus(404), .httpStatus(503)])
  }

  /// An unclassifiable failure, retried and given up on, reports the context's fallback.
  @Test func givingUpOnAnOpaqueFailureReportsTheFallback() async {
    let (loop, record) = makeLoop(retries: false)
    loop.handleError(NSError(domain: "CoreMediaErrorDomain", code: -12939), context: .mediaLoad)
    await loop.pendingTask?.value
    #expect(record.errors == [.failedToLoadKeyValue])
  }

  @Test func resetDropsTheRetryAndTheBudgets() async {
    let (loop, record) = makeLoop()
    loop.handleError(URLError(.timedOut), context: .playback)
    await loop.pendingTask?.value
    #expect(loop.attempts == 1)

    let delayed = makeRetryLoop(delay: 0.02)
    delayed.handleError(URLError(.timedOut), context: .playback)
    let task = delayed.pendingTask
    delayed.reset()
    loop.reset()

    #expect(delayed.pendingTask == nil)
    #expect(loop.attempts == 0)
    #expect(record.errors.isEmpty)
    await task?.value
  }
}
