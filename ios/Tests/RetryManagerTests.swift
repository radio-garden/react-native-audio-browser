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
