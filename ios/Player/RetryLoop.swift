import AVFoundation
import Foundation
#if canImport(NitroModules)
  import NitroModules
#endif
import os.log

/// Connectivity as `RetryLoop` sees it; a fake stands in for `NetworkMonitor` in tests. Sendable
/// because the network-restore wait reads it from a task-group child.
protocol NetworkStatusProviding: AnyObject, Sendable {
  var isOnline: Bool { get }
}

/// Where a failure came from, which decides where the retry resumes and the error reported when
/// the failure cannot be classified.
public enum PlaybackErrorContext {
  /// An AVPlayer/AVPlayerItem failure: retries resume from the current time.
  case playback
  /// A MediaLoader key-value load failure: retries start from the beginning.
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

/// Retries failed loads with exponential backoff until a budget runs out, then reports the error
/// as terminal; the iOS counterpart of Android's RetryLoadErrorHandlingPolicy.
///
/// Two duration budgets apply, chosen by whether the current Load has produced audio (`hasPlayed`,
/// docs/adr/0004): a short first-connect budget for a stream that never played (usually dead, with
/// the listener waiting for a verdict), counted only while online, and the full recovery budget for
/// one that played and dropped. While offline, a
/// scheduled retry fires as soon as connectivity returns.
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

  // Bounds everything, offline waits included, so playback does not resume by surprise after a
  // long time offline.
  private var maxRetryDuration: TimeInterval = defaultMaxRetryDurationMs / 1000
  private var firstConnectMaxRetryDuration: TimeInterval = defaultFirstConnectMaxRetryDurationMs / 1000

  /// Whether the current Load has produced audio. Read from the Load, not kept here, because a
  /// healthy-playback `reset()` must not clear it.
  var hasPlayed: () -> Bool = { false }
  /// The play intent; checked before and after the backoff.
  var shouldRetry: () -> Bool = { true }
  /// Reloads; the argument is true to resume from the current time, false to start over.
  var onRetry: ((Bool) -> Void)?
  /// Terminal: the retries gave up, or the failure cannot be retried. Not called when the retry
  /// is cancelled.
  var onError: ((TrackPlayerError.PlaybackError) -> Void)?
  /// A failure a retry is now scheduled for, reported before the backoff while the state stays
  /// non-terminal.
  var onRetryingError: ((TrackPlayerError.PlaybackError) -> Void)?
  /// A retryable failure with no play intent: nothing retries it and nothing reports it.
  var onFailedWithoutIntent: (() -> Void)?

  weak var networkMonitor: (any NetworkStatusProviding)?

  /// The retry in flight; session release waits while there is one.
  private(set) var pendingTask: Task<Void, Never>?
  private(set) var attempts = 0

  /// Continuous audible playback after which the budgets refill (Android's HEALTHY_PLAYBACK_MS).
  var healthyPlaybackDuration: Duration = .seconds(20)
  private(set) var healthyPlaybackTask: Task<Void, Never>?

  private var policy: Policy = .disabled
  private var firstRetryTime: Date?
  // The first-connect budget's clock: never set while offline and wiped by any offline
  // observation, so an offline stretch cannot burn the short budget.
  private var firstOnlineRetryTime: Date?

  private let sleep: @Sendable (TimeInterval) async throws -> Void

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
      policy = retryConfig.maxRetries.map { .limited(maxRetries: Int($0)) } ?? .infinite
    }
    logger.debug("Retry policy: \(String(describing: self.policy)), max duration \(self.maxRetryDuration)s")
  }

  // MARK: - Failures

  /// Reports a non-retryable failure through `onError` at once, and one with no play intent
  /// through `onFailedWithoutIntent`; otherwise reports it through `onRetryingError`, replaces any
  /// retry in flight, and calls `onError` only if that retry gives up.
  func handleError(_ error: Error?, context: PlaybackErrorContext, httpStatusCode: Int? = nil) {
    if let error {
      let nsError = error as NSError
      logger.error("[\(String(describing: context))] failure: domain=\(nsError.domain), code=\(nsError.code), localizedDescription=\(error.localizedDescription)")
    } else {
      logger.error("[\(String(describing: context))] failure with nil error")
    }

    let failure = PlaybackFailure.classify(error, httpStatusCode: httpStatusCode, fallback: context.fallbackError)
    guard isEnabled, failure.retryable else {
      let reason = isEnabled ? "Error not retryable" : "Retry disabled"
      logger.warning("\(reason), surfacing \(String(describing: failure.error))")
      onError?(failure.error)
      return
    }
    guard shouldRetry() else {
      logger.info("No play intent: \(String(describing: failure.error)) is left to the next play")
      onFailedWithoutIntent?()
      return
    }
    onRetryingError?(failure.error)
    pendingTask?.cancel()
    pendingTask = Task { [weak self] in
      guard let self else { return }
      let retried = await attemptRetry(startFromCurrentTime: context.startFromCurrentTime)
      // Cancelled by a track change or stop: stay silent, or the new track or the stop would
      // flip to .error. The canceller owns `pendingTask`.
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

  /// Drops the retry in flight and refills the budgets.
  func reset() {
    cancel()
    attempts = 0
    firstRetryTime = nil
    firstOnlineRetryTime = nil
  }

  // MARK: - Healthy Playback

  /// Starts the healthy-playback window; if it elapses with no retry pending, the budgets refill.
  func playbackBecameAudible() {
    healthyPlaybackTask?.cancel()
    healthyPlaybackTask = Task { [weak self, healthyPlaybackDuration] in
      try? await Task.sleep(for: healthyPlaybackDuration)
      guard let self, !Task.isCancelled else { return }
      // reset() would cancel the pending retry and silently kill an in-flight reconnect.
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

  private var backoff: TimeInterval {
    min(1.0 * pow(1.5, Double(attempts)), 5.0)
  }

  /// Waits out the backoff, or connectivity's return, then calls `onRetry`. False when the policy,
  /// the play intent or a budget says stop, or the wait was cancelled.
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
    // The wait for connectivity may have been long.
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

  private func waitForNetworkRestored(monitor: any NetworkStatusProviding) async -> Bool {
    while !monitor.isOnline {
      guard await (try? sleep(0.5)) != nil else { return false }
    }
    logger.info("Network restored, accelerating retry")
    return true
  }
}
