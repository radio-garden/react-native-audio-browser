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

/// Manages retry logic for media load errors with exponential backoff.
/// Similar to Android's RetryLoadErrorHandlingPolicy.
///
/// Two duration budgets apply, chosen by whether the current load has ever
/// produced audio (`hasPlayed`): a short first-connect budget — a stream that
/// fails before ever playing is usually dead, and the listener is actively
/// waiting for a verdict — and the full recovery budget for a stream that
/// played and then dropped (tunnels, handovers, encoder restarts). The short
/// budget counts only online time, so a station tapped in a tunnel still gets
/// its online seconds once connectivity returns.
///
/// When a network monitor is provided and the device is offline, the retry will
/// trigger immediately when connectivity is restored instead of waiting for the
/// full backoff delay.
@MainActor
class RetryManager {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "RetryManager")

  enum Policy {
    case disabled
    case infinite
    case limited(maxRetries: Int)
  }

  private static let defaultMaxRetryDurationMs: Double = 120_000 // 2 minutes
  private static let defaultFirstConnectMaxRetryDurationMs: Double = 12_000

  /// Prevents surprising playback resumption after long periods offline.
  private var maxRetryDuration: TimeInterval = defaultMaxRetryDurationMs / 1000

  /// Bounds retries of a load that has never produced audio, while online.
  private var firstConnectMaxRetryDuration: TimeInterval = defaultFirstConnectMaxRetryDurationMs / 1000

  /// True once the current load has produced audio; selects between the
  /// first-connect and recovery budgets. Set by the owner when audio starts and
  /// cleared by the owner when a new track loads — deliberately NOT cleared in
  /// `reset()`, which the healthy-playback budget refill also calls mid-play.
  var hasPlayed = false

  /// First failure observed while online for the current load — the
  /// first-connect budget's clock. Never set while offline, and wiped by any
  /// offline observation, so an offline stretch cannot burn the short budget:
  /// the clock restarts at the next failure observed online.
  private var firstOnlineRetryTime: Date?

  private var policy: Policy = .disabled
  private var attemptCount = 0
  private var firstRetryTime: Date?

  weak var networkMonitor: (any NetworkStatusProviding)?
  /// True only while a scheduled retry is parked polling for connectivity to return. Exposed so the
  /// player's stall-driven reconnect can defer to an in-flight error retry (which owns its own
  /// reload) and avoid a double load.
  private(set) var isWaitingForNetwork = false
  /// Invalidates in-flight retries when reset() is called (e.g. track change)
  private var generation: Int = 0

  var shouldRetry: () -> Bool = { true }
  var onRetry: ((Bool) -> Void)?

  var isEnabled: Bool {
    if case .disabled = policy { return false }
    return true
  }

  // MARK: - Configuration

  func updatePolicy(from config: Variant_Bool_RetryConfig?) {
    guard let config else {
      policy = .disabled
      maxRetryDuration = Self.defaultMaxRetryDurationMs / 1000
      firstConnectMaxRetryDuration = Self.defaultFirstConnectMaxRetryDurationMs / 1000
      logger.debug("Retry policy: disabled")
      return
    }

    switch config {
    case let .first(enabled):
      policy = enabled ? .infinite : .disabled
      maxRetryDuration = Self.defaultMaxRetryDurationMs / 1000
      firstConnectMaxRetryDuration = Self.defaultFirstConnectMaxRetryDurationMs / 1000
      logger.debug("Retry policy: \(enabled ? "infinite" : "disabled")")
    case let .second(retryConfig):
      let durationMs = retryConfig.maxRetryDurationMs ?? Self.defaultMaxRetryDurationMs
      maxRetryDuration = durationMs / 1000
      let firstConnectMs = retryConfig.firstConnectMaxRetryDurationMs
        ?? Self.defaultFirstConnectMaxRetryDurationMs
      firstConnectMaxRetryDuration = firstConnectMs / 1000
      // No attempt cap = retry indefinitely, bounded only by the duration.
      if let maxRetries = retryConfig.maxRetries.map(Int.init) {
        policy = .limited(maxRetries: maxRetries)
        logger.debug("Retry policy: limited to \(maxRetries) retries, max duration \(self.maxRetryDuration)s")
      } else {
        policy = .infinite
        logger.debug("Retry policy: infinite, max duration \(self.maxRetryDuration)s")
      }
    }
  }

  // MARK: - Exponential Backoff

  /// Delays: 1s -> 1.5s -> 2.3s -> 3.4s -> 5s (capped)
  private func calculateDelaySeconds() -> Double {
    let baseDelay = 1.0
    let multiplier = 1.5
    let maxDelay = 5.0

    return min(baseDelay * pow(multiplier, Double(attemptCount)), maxDelay)
  }

  // MARK: - Error Classification

  /// HTTP statuses worth another attempt, beyond the 500–599 range checked in
  /// `isRetryable`. Android's `RetryLoadErrorHandlingPolicy` shares 408/429 but
  /// enumerates only 500/502/503/504 of the 5xx range, so uncommon 5xx codes
  /// (501, 505…) retry here and not there.
  private static let retryableHTTPStatusCodes: Set<Int> = [408, 429]

  /// AVFoundation failures a retry cannot fix: the media is unusable rather
  /// than the transport unreliable. Everything not listed falls through to
  /// retry — `AVErrorUnknown` especially, which is the wrapper AVFoundation
  /// puts around opaque CoreMedia transport failures.
  private static let fatalAVErrorCodes: Set<Int> = [
    AVError.fileFormatNotRecognized.rawValue,
    AVError.failedToParse.rawValue,
    AVError.decodeFailed.rawValue,
    AVError.contentIsProtected.rawValue,
    AVError.contentIsNotAuthorized.rawValue,
  ]

  /// Whether another attempt could plausibly succeed.
  ///
  /// Mirrors Android's `classifyError`, including its default: an unrecognized
  /// failure is treated as *transient* and retried, bounded by the retry
  /// budgets, rather than being terminal. Defaulting the other way made
  /// `retry: true` largely inert on iOS, because AVFoundation reports most
  /// stream failures as opaque `CoreMediaErrorDomain` / `AVFoundationErrorDomain`
  /// errors rather than `URLError`.
  ///
  /// - Parameter httpStatusCode: The status from the item's error log, when the
  ///   caller has one. `AVPlayerItem.error` never carries it — a 404 arrives as
  ///   an opaque CoreMedia error — so this is the only reliable HTTP signal and
  ///   it outranks the error itself.
  func isRetryable(_ error: Error?, httpStatusCode: Int?) -> Bool {
    if let status = httpStatusCode {
      let retryable = Self.retryableHTTPStatusCodes.contains(status) || (500 ... 599).contains(status)
      logger.debug("HTTP \(status) \(retryable ? "is" : "not") retryable")
      return retryable
    }

    guard let error else { return false }

    // Walk the underlying-error chain: AVFoundation routinely wraps the real
    // cause (a URLError, or a CoreMedia code) in an AVError.
    for candidate in Self.errorChain(error) {
      if let urlError = candidate as? URLError {
        switch urlError.code {
        // Transient network errors - safe to retry
        case .timedOut,
             .networkConnectionLost,
             .notConnectedToInternet,
             .cannotConnectToHost,
             .cannotFindHost,
             .dnsLookupFailed:
          logger.debug("URLError is retryable: code=\(urlError.code.rawValue)")
          return true
        default:
          logger.debug("URLError not retryable: code=\(urlError.code.rawValue)")
          return false
        }
      }

      let nsError = candidate as NSError
      if nsError.domain == AVFoundationErrorDomain,
         Self.fatalAVErrorCodes.contains(nsError.code)
      {
        logger.debug("AVError not retryable: code=\(nsError.code)")
        return false
      }
    }

    let nsError = error as NSError
    logger.debug("Treating as transient: domain=\(nsError.domain), code=\(nsError.code)")
    return true
  }

  /// The error and its `NSUnderlyingError` ancestors, outermost first.
  private static func errorChain(_ error: Error) -> [Error] {
    var chain: [Error] = []
    var current: Error? = error
    // Bounded: a malformed cycle must not spin here.
    while let error = current, chain.count < 8 {
      chain.append(error)
      current = (error as NSError).userInfo[NSUnderlyingErrorKey] as? Error
    }
    return chain
  }

  // MARK: - Retry Management

  func reset() {
    attemptCount = 0
    firstRetryTime = nil
    firstOnlineRetryTime = nil
    isWaitingForNetwork = false
    generation += 1
    logger.debug("Retry count reset (generation \(self.generation))")
  }

  private var isOnline: Bool { networkMonitor?.isOnline ?? true }

  /// A description of the exhausted budget, or nil while budget remains.
  /// The recovery duration bounds everything (including offline waits); the
  /// first-connect duration additionally bounds never-played loads, online only.
  private func exhaustedBudget() -> String? {
    let now = Date()
    if let start = firstRetryTime, now.timeIntervalSince(start) >= maxRetryDuration {
      return "max retry duration (\(maxRetryDuration)s)"
    }
    if !hasPlayed, isOnline, let start = firstOnlineRetryTime,
       now.timeIntervalSince(start) >= firstConnectMaxRetryDuration
    {
      return "first-connect retry duration (\(firstConnectMaxRetryDuration)s)"
    }
    return nil
  }

  func attemptRetry(startFromCurrentTime: Bool) async -> Bool {
    if case .disabled = policy {
      logger.debug("Retry disabled, not retrying")
      return false
    }

    guard shouldRetry() else {
      logger.debug("shouldRetry returned false, not retrying")
      return false
    }

    if case let .limited(maxRetries) = policy {
      if attemptCount >= maxRetries {
        logger.info("Max retries (\(maxRetries)) exceeded, giving up")
        return false
      }
    }

    if firstRetryTime == nil {
      firstRetryTime = Date()
    }
    if isOnline {
      if firstOnlineRetryTime == nil { firstOnlineRetryTime = Date() }
    } else {
      // Seeing the device offline restarts the first-connect clock: a station
      // that lost connectivity mid-budget gets its full online seconds again
      // after restoration, instead of the offline gap counting against it.
      firstOnlineRetryTime = nil
    }

    // Check if we've been retrying too long (prevents surprising resumption
    // after long offline periods, and grants dead-on-arrival streams a fast verdict)
    if let budget = exhaustedBudget() {
      logger.info("\(budget) exceeded, giving up")
      return false
    }

    let delaySeconds = calculateDelaySeconds()
    let currentGeneration = generation
    logger.info("Scheduling retry after \(delaySeconds)s")

    // Race between: backoff delay OR network restored (if offline)
    let waitCancelled = await waitForDelayOrNetworkRestored(delaySeconds: delaySeconds)

    if waitCancelled {
      logger.debug("Retry wait cancelled")
      return false
    }

    // Check if reset() was called while we were waiting
    guard generation == currentGeneration else {
      logger.debug("Retry invalidated by reset (generation changed)")
      return false
    }

    // Cancellation can land after the sleep completed — without this check a
    // replaced retry (new error right as the old backoff expires) still fires
    // onRetry, double-reloading and burning the attempt count twice.
    guard !Task.isCancelled else { return false }

    guard shouldRetry() else {
      logger.debug("shouldRetry returned false after delay, cancelling retry")
      return false
    }

    // Re-check the budgets — we may have waited a long time for network restoration
    if let budget = exhaustedBudget() {
      logger.info("\(budget) exceeded after waiting, giving up")
      return false
    }

    attemptCount += 1
    logger.info("Executing retry #\(self.attemptCount)")
    onRetry?(startFromCurrentTime)
    return true
  }

  // MARK: - Network-Aware Waiting

  /// Returns true if cancelled, false if ready to retry.
  private func waitForDelayOrNetworkRestored(delaySeconds: Double) async -> Bool {
    let isOffline = !(networkMonitor?.isOnline ?? true)

    guard isOffline, let monitor = networkMonitor else {
      do {
        let nanoseconds = UInt64(delaySeconds * 1_000_000_000)
        try await Task.sleep(nanoseconds: nanoseconds)
        return false
      } catch {
        return true // Cancelled
      }
    }

    logger.debug("Device is offline, will retry immediately when connectivity is restored")

    return await withTaskGroup(of: Bool.self, returning: Bool.self) { group in
      group.addTask {
        do {
          let nanoseconds = UInt64(delaySeconds * 1_000_000_000)
          try await Task.sleep(nanoseconds: nanoseconds)
          return false
        } catch {
          return true
        }
      }

      group.addTask { [weak self] in
        await self?.waitForNetworkRestored(monitor: monitor) ?? true
      }

      let result = await group.next() ?? true
      group.cancelAll()
      return result
    }
  }

  /// Returns false when network is restored, true if cancelled.
  @MainActor
  private func waitForNetworkRestored(monitor: any NetworkStatusProviding) async -> Bool {
    if monitor.isOnline {
      return false
    }

    isWaitingForNetwork = true
    defer { isWaitingForNetwork = false }

    logger.debug("Device is offline, polling for network restoration")

    while !monitor.isOnline {
      do {
        try await Task.sleep(nanoseconds: 500_000_000) // 0.5s poll
      } catch {
        return true // Task cancelled
      }
    }

    logger.info("Network restored, accelerating retry")
    return false
  }
}

// MARK: - RetryHandling

extension RetryManager: RetryHandling {}
