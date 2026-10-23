import AVFoundation
import Foundation
#if canImport(NitroModules)
  import NitroModules
#endif
import os.log

/// Abstracts `NetworkMonitor` so tests can fake connectivity. Sendable because
/// the network-restore race reads it from a task-group child.
protocol NetworkStatusProviding: AnyObject, Sendable {
  var isOnline: Bool { get }
}

/// Distinguishes playback failures from media-load failures.
/// Each context carries the retry strategy and the fallback error classification.
public enum PlaybackErrorContext {
  /// AVPlayer/AVPlayerItem status failure — retry from current time, fallback `.playbackFailed`.
  case playback
  /// MediaLoader key-value load failure — retry from start, fallback `.failedToLoadKeyValue`.
  case mediaLoad

  var startFromCurrentTime: Bool {
    switch self {
    case .playback: true
    case .mediaLoad: false
    }
  }

  var fallbackError: TrackPlayerError.PlaybackError {
    switch self {
    case .playback: .playbackFailed
    case .mediaLoad: .failedToLoadKeyValue
    }
  }
}

/// Retries failed loads: classifies each failure (`PlaybackFailure`), reports the ones it is
/// working on as advisory, and reloads after an exponential backoff until a budget runs out,
/// when it reports the error as terminal. Similar to Android's RetryLoadErrorHandlingPolicy.
///
/// Two duration budgets apply, chosen by whether the current Load has produced audio
/// (`hasPlayed`, ADR 0004): a short first-connect budget, since a stream that fails before ever
/// playing is usually dead and the listener is waiting for a verdict, and the full recovery
/// budget for a stream that played and dropped. The short budget counts only online time, and
/// while offline a scheduled retry fires as soon as connectivity returns.
@MainActor
final class RetryLoop {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "RetryLoop")

  enum Policy {
    case disabled
    case infinite
    case limited(maxRetries: Int)
  }

  private static let defaultMaxRetryDurationMs: Double = 120_000
  private static let defaultFirstConnectMaxRetryDurationMs: Double = 12_000

  /// Prevents surprising playback resumption after long periods offline.
  private var maxRetryDuration: TimeInterval = defaultMaxRetryDurationMs / 1000
  /// Bounds retries of a load that has never produced audio, while online.
  private var firstConnectMaxRetryDuration: TimeInterval = defaultFirstConnectMaxRetryDurationMs / 1000

  /// Whether the current Load has produced audio. Read from the Load, which a mid-play `reset()`
  /// (the healthy-playback refill) leaves alone.
  var hasPlayed: () -> Bool = { false }
  /// Whether a retry is still wanted (the play intent); checked before and after the backoff.
  var shouldRetry: () -> Bool = { true }
  /// Reloads, from the current time or from the start.
  var onRetry: ((Bool) -> Void)?
  /// The retries gave up, or the failure cannot be retried.
  var onError: ((TrackPlayerError.PlaybackError) -> Void)?
  /// A failure a retry is now scheduled for, reported before the backoff so UIs can show the
  /// cause while the state stays non-terminal.
  var onRetryingError: ((TrackPlayerError.PlaybackError) -> Void)?

  weak var networkMonitor: (any NetworkStatusProviding)?

  /// The retry in flight; session release waits while there is one.
  private(set) var pendingTask: Task<Void, Never>?
  /// True only while a scheduled retry waits for connectivity to return, so a stall-driven
  /// reconnect can defer to it rather than load twice.
  private(set) var isWaitingForNetwork = false
  /// Retries executed since the budgets last reset.
  private(set) var attempts = 0

  /// Continuous audible playback that proves the stream recovered, so the budgets refill
  /// (Android's HEALTHY_PLAYBACK_MS); without it a long-lived stream keeps its spent budget until
  /// the track changes.
  var healthyPlaybackDuration: Duration = .seconds(20)
  /// The pending refill. Readable so tests can await the window instead of sleeping past it.
  private(set) var healthyPlaybackTask: Task<Void, Never>?

  private var policy: Policy = .disabled
  private var firstRetryTime: Date?
  /// First failure observed while online for the current load: the first-connect budget's
  /// clock. Never set while offline, and wiped by any offline observation, so an offline stretch
  /// cannot burn the short budget.
  private var firstOnlineRetryTime: Date?

  private let sleep: @Sendable (TimeInterval) async throws -> Void

  /// - Parameter sleep: Waits out a backoff or a connectivity poll; tests make it instant.
  init(
    networkMonitor: (any NetworkStatusProviding)? = nil,
    sleep: @escaping @Sendable (TimeInterval) async throws -> Void = { try await Task.sleep(for: .seconds($0)) },
  ) {
    self.networkMonitor = networkMonitor
    self.sleep = sleep
  }

  var isEnabled: Bool {
    if case .disabled = policy { return false }
    return true
  }

  // MARK: - Configuration

  func updatePolicy(from config: Variant_Bool_RetryConfig?) {
    maxRetryDuration = Self.defaultMaxRetryDurationMs / 1000
    firstConnectMaxRetryDuration = Self.defaultFirstConnectMaxRetryDurationMs / 1000
    switch config {
    case nil:
      policy = .disabled
    case let .first(enabled):
      policy = enabled ? .infinite : .disabled
    case let .second(retryConfig):
      maxRetryDuration = (retryConfig.maxRetryDurationMs ?? Self.defaultMaxRetryDurationMs) / 1000
      firstConnectMaxRetryDuration =
        (retryConfig.firstConnectMaxRetryDurationMs ?? Self.defaultFirstConnectMaxRetryDurationMs) / 1000
      // No attempt cap: retry indefinitely, bounded only by the duration.
      policy = retryConfig.maxRetries.map { .limited(maxRetries: Int($0)) } ?? .infinite
    }
    logger.debug("Retry policy: \(String(describing: self.policy)), max duration \(self.maxRetryDuration)s")
  }

  // MARK: - Failures

  func handleError(_ error: Error?, context: PlaybackErrorContext, httpStatusCode: Int? = nil) {
    if let error {
      let nsError = error as NSError
      logger.error("[\(String(describing: context))] failure: domain=\(nsError.domain), code=\(nsError.code), localizedDescription=\(error.localizedDescription)")
    } else {
      logger.error("[\(String(describing: context))] failure with nil error")
    }

    let failure = PlaybackFailure.classify(error, httpStatusCode: httpStatusCode, fallback: context.fallbackError)
    guard isEnabled, failure.retryable else {
      logger.warning("Error not retryable, surfacing \(String(describing: failure.error))")
      onError?(failure.error)
      return
    }
    onRetryingError?(failure.error)
    pendingTask?.cancel()
    pendingTask = Task { [weak self] in
      guard let self else { return }
      let retried = await attemptRetry(startFromCurrentTime: context.startFromCurrentTime)
      // Cancelled (track change, stop): stay silent, or the new track or a deliberate stop
      // would flip to .error. The canceller owns the handle.
      if Task.isCancelled { return }
      if !retried { onError?(failure.error) }
      pendingTask = nil
    }
  }

  /// Drops the retry in flight without touching the budgets.
  func cancel() {
    pendingTask?.cancel()
    pendingTask = nil
  }

  /// Drops the retry in flight and restarts the budgets.
  func reset() {
    cancel()
    attempts = 0
    firstRetryTime = nil
    firstOnlineRetryTime = nil
    isWaitingForNetwork = false
  }

  // MARK: - Healthy Playback

  /// Starts the healthy-playback window; when it elapses between failures, the budgets refill.
  func playbackBecameAudible() {
    healthyPlaybackTask?.cancel()
    healthyPlaybackTask = Task { [weak self, healthyPlaybackDuration] in
      try? await Task.sleep(for: healthyPlaybackDuration)
      guard let self, !Task.isCancelled else { return }
      // reset() cancels a pending retry, which would silently kill an in-flight reconnect.
      guard pendingTask == nil else { return }
      reset()
    }
  }

  func playbackStoppedBeingAudible() {
    healthyPlaybackTask?.cancel()
    healthyPlaybackTask = nil
  }

  // MARK: - Budgets

  private var isOnline: Bool { networkMonitor?.isOnline ?? true }

  /// The exhausted budget, or nil while budget remains. The recovery duration bounds everything,
  /// offline waits included; the first-connect duration also bounds never-played loads, online
  /// only.
  private func exhaustedBudget() -> String? {
    let now = Date()
    if let start = firstRetryTime, now.timeIntervalSince(start) >= maxRetryDuration {
      return "max retry duration (\(maxRetryDuration)s)"
    }
    if !hasPlayed(), isOnline, let start = firstOnlineRetryTime,
       now.timeIntervalSince(start) >= firstConnectMaxRetryDuration
    {
      return "first-connect retry duration (\(firstConnectMaxRetryDuration)s)"
    }
    return nil
  }

  /// 1 s, 1.5 s, 2.3 s, 3.4 s, then 5 s.
  private var backoff: TimeInterval {
    min(1.0 * pow(1.5, Double(attempts)), 5.0)
  }

  /// Waits out the backoff, or connectivity's return, then reloads. False when the policy, the
  /// play intent or a budget says stop, or the wait was cancelled.
  func attemptRetry(startFromCurrentTime: Bool) async -> Bool {
    guard isEnabled, shouldRetry() else { return false }
    if case let .limited(maxRetries) = policy, attempts >= maxRetries {
      logger.info("Max retries (\(maxRetries)) exceeded, giving up")
      return false
    }

    if firstRetryTime == nil { firstRetryTime = Date() }
    if isOnline {
      if firstOnlineRetryTime == nil { firstOnlineRetryTime = Date() }
    } else {
      // Offline restarts the first-connect clock: the station gets its full online seconds
      // again once connectivity returns.
      firstOnlineRetryTime = nil
    }
    if let budget = exhaustedBudget() {
      logger.info("\(budget) exceeded, giving up")
      return false
    }

    logger.info("Scheduling retry after \(self.backoff)s")
    guard await waitForDelayOrNetworkRestored(delaySeconds: backoff), !Task.isCancelled, shouldRetry() else {
      return false
    }
    // Re-checked: the wait for connectivity may have been long.
    if let budget = exhaustedBudget() {
      logger.info("\(budget) exceeded after waiting, giving up")
      return false
    }

    attempts += 1
    logger.info("Executing retry #\(self.attempts)")
    onRetry?(startFromCurrentTime)
    return true
  }

  // MARK: - Network-Aware Waiting

  /// True when it is time to retry, false when the wait was cancelled.
  private func waitForDelayOrNetworkRestored(delaySeconds: TimeInterval) async -> Bool {
    guard let monitor = networkMonitor, !monitor.isOnline else {
      return await (try? sleep(delaySeconds)) != nil
    }
    logger.debug("Device is offline, will retry immediately when connectivity is restored")
    let sleep = sleep
    return await withTaskGroup(of: Bool.self, returning: Bool.self) { group in
      group.addTask { await (try? sleep(delaySeconds)) != nil }
      group.addTask { [weak self] in await self?.waitForNetworkRestored(monitor: monitor) ?? false }
      let result = await group.next() ?? false
      group.cancelAll()
      return result
    }
  }

  /// True when connectivity returned, false when cancelled.
  private func waitForNetworkRestored(monitor: any NetworkStatusProviding) async -> Bool {
    isWaitingForNetwork = true
    defer { isWaitingForNetwork = false }
    while !monitor.isOnline {
      guard await (try? sleep(0.5)) != nil else { return false }
    }
    logger.info("Network restored, accelerating retry")
    return true
  }
}
