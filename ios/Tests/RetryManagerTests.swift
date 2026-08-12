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
@Suite("RetryManager budgets")
@MainActor
struct RetryManagerBudgetTests {
  /// Returns the mock too — `networkMonitor` is weak, the test must keep it alive.
  private func makeManager(
    firstConnectMs: Double,
    maxMs: Double = 120_000,
    online: Bool,
  ) -> (RetryManager, MockNetworkStatus) {
    let manager = RetryManager()
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
    manager.hasPlayed = true

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

  /// `reset()` (track change / budget refill) restarts the clocks but must NOT
  /// touch `hasPlayed` — the refill runs mid-play, and clearing the flag there
  /// would hand a playing stream the short budget on its next drop.
  @Test func reset_keepsHasPlayed() {
    let manager = RetryManager()
    manager.hasPlayed = true
    manager.reset()
    #expect(manager.hasPlayed == true)
  }
}

/// Which failures earn another attempt. The reference is Android's
/// `RetryLoadErrorHandlingPolicy.classifyError`: same HTTP set, and the same
/// default of treating an unrecognized failure as transient. Defaulting the
/// other way made `retry: true` largely inert on iOS, since AVFoundation
/// reports most stream failures as opaque CoreMedia errors, not `URLError`.
@Suite("RetryManager error classification")
@MainActor
struct RetryManagerClassificationTests {
  private func makeManager() -> RetryManager {
    let manager = RetryManager()
    manager.updatePolicy(from: .first(true))
    return manager
  }

  /// `AVPlayerItem.error` never carries the status, so the caller reads it off
  /// the item's error log and passes it in. It outranks the opaque error.
  @Test(arguments: [408, 429, 500, 502, 503, 504])
  func retryableHTTPStatus_retries(status: Int) {
    let opaque = NSError(domain: "CoreMediaErrorDomain", code: -12660)
    #expect(makeManager().isRetryable(opaque, httpStatusCode: status) == true)
  }

  /// A retry cannot conjure content that isn't there, whatever the error says.
  @Test(arguments: [400, 401, 403, 404, 410, 451])
  func permanentHTTPStatus_doesNotRetry(status: Int) {
    let transient = URLError(.timedOut)
    #expect(makeManager().isRetryable(transient, httpStatusCode: status) == false)
  }

  @Test func transientURLError_retries() {
    #expect(makeManager().isRetryable(URLError(.networkConnectionLost), httpStatusCode: nil) == true)
  }

  @Test func fatalURLError_doesNotRetry() {
    #expect(makeManager().isRetryable(URLError(.badURL), httpStatusCode: nil) == false)
  }

  /// The regression this suite exists for: an opaque CoreMedia failure with no
  /// status to read used to be terminal, so a station that dropped mid-song
  /// never came back on iOS while Android reconnected.
  @Test func opaqueCoreMediaError_retries() {
    let error = NSError(domain: "CoreMediaErrorDomain", code: -12939)
    #expect(makeManager().isRetryable(error, httpStatusCode: nil) == true)
  }

  /// `AVErrorUnknown` is the wrapper AVFoundation puts around transport
  /// failures, so it must stay retryable even though it is an AVError.
  @Test func avErrorUnknown_retries() {
    let error = NSError(domain: AVFoundationErrorDomain, code: AVError.unknown.rawValue)
    #expect(makeManager().isRetryable(error, httpStatusCode: nil) == true)
  }

  @Test(arguments: [
    AVError.fileFormatNotRecognized.rawValue,
    AVError.failedToParse.rawValue,
    AVError.decodeFailed.rawValue,
  ])
  func unusableMedia_doesNotRetry(code: Int) {
    let error = NSError(domain: AVFoundationErrorDomain, code: code)
    #expect(makeManager().isRetryable(error, httpStatusCode: nil) == false)
  }

  /// AVFoundation wraps the real cause rather than surfacing it, which is why
  /// the classifier walks `NSUnderlyingError` instead of inspecting only the
  /// outermost error.
  @Test func underlyingURLError_isFound() {
    let manager = makeManager()
    let wrapped = NSError(
      domain: AVFoundationErrorDomain,
      code: AVError.unknown.rawValue,
      userInfo: [NSUnderlyingErrorKey: URLError(.badURL)],
    )
    #expect(manager.isRetryable(wrapped, httpStatusCode: nil) == false)

    let transient = NSError(
      domain: AVFoundationErrorDomain,
      code: AVError.unknown.rawValue,
      userInfo: [NSUnderlyingErrorKey: URLError(.dnsLookupFailed)],
    )
    #expect(manager.isRetryable(transient, httpStatusCode: nil) == true)
  }

  /// Nothing to classify: no error and no status stays terminal.
  @Test func nilError_doesNotRetry() {
    #expect(makeManager().isRetryable(nil, httpStatusCode: nil) == false)
  }
}
