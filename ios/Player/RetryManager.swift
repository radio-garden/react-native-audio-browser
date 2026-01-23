import Foundation
import NitroModules
import os.log

/// Manages retry logic for media load errors with exponential backoff.
/// Similar to Android's RetryLoadErrorHandlingPolicy.
///
/// When a network monitor is provided and the device is offline, the retry will
/// trigger immediately when connectivity is restored instead of waiting for the
/// full backoff delay.
@MainActor
class RetryManager {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "RetryManager")

  /// Retry policy configuration
  enum Policy {
    case disabled
    case infinite
    case limited(maxRetries: Int)
  }

  /// Default maximum duration to keep retrying before giving up (in milliseconds).
  private static let defaultMaxRetryDurationMs: Double = 120_000 // 2 minutes

  /// Maximum duration to keep retrying before giving up (in seconds).
  /// This prevents surprising playback resumption after long periods offline.
  private var maxRetryDuration: TimeInterval = defaultMaxRetryDurationMs / 1000

  private var policy: Policy = .disabled
  private var attemptCount = 0
  private var firstRetryTime: Date?

  /// Network monitor for accelerating retries when connectivity is restored
  weak var networkMonitor: NetworkMonitor?

  /// Flag to track if we're waiting for network (for cleanup on reset)
  private var isWaitingForNetwork = false

  /// Callback to check if we should retry (typically checks playWhenReady)
  var shouldRetry: () -> Bool = { true }

  /// Callback to trigger reload
  var onRetry: ((Bool) -> Void)?

  // MARK: - Configuration

  /// Updates the retry policy from the Nitro config type
  func updatePolicy(from config: Variant_Bool_RetryConfig?) {
    guard let config else {
      policy = .disabled
      maxRetryDuration = Self.defaultMaxRetryDurationMs / 1000
      logger.debug("Retry policy: disabled")
      return
    }

    switch config {
    case let .first(enabled):
      policy = enabled ? .infinite : .disabled
      maxRetryDuration = Self.defaultMaxRetryDurationMs / 1000
      logger.debug("Retry policy: \(enabled ? "infinite" : "disabled")")
    case let .second(retryConfig):
      let maxRetries = Int(retryConfig.maxRetries)
      policy = .limited(maxRetries: maxRetries)
      // Use configured duration or default
      let durationMs = retryConfig.maxRetryDurationMs ?? Self.defaultMaxRetryDurationMs
      maxRetryDuration = durationMs / 1000
      logger.debug("Retry policy: limited to \(maxRetries) retries, max duration \(self.maxRetryDuration)s")
    }
  }

  // MARK: - Exponential Backoff

  /// Calculates delay for current attempt using exponential backoff.
  /// Delays: 1s -> 1.5s -> 2.3s -> 3.4s -> 5s (capped)
  /// Returns delay in seconds.
  private func calculateDelaySeconds() -> Double {
    let baseDelay = 1.0
    let multiplier = 1.5
    let maxDelay = 5.0

    return min(baseDelay * pow(multiplier, Double(attemptCount)), maxDelay)
  }

  // MARK: - Error Classification

  /// Classifies whether an error is retryable or permanent.
  /// Only clearly transient network errors are retried.
  func isRetryable(_ error: Error?) -> Bool {
    guard let error else { return false }

    if let urlError = error as? URLError {
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

    // Unknown errors - don't retry
    let nsError = error as NSError
    logger.debug("Error not retryable: domain=\(nsError.domain), code=\(nsError.code)")
    return false
  }

  // MARK: - Retry Management

  /// Resets retry count. Call when track changes.
  func reset() {
    attemptCount = 0
    firstRetryTime = nil
    isWaitingForNetwork = false
    logger.debug("Retry count reset")
  }

  /// Attempts a retry if policy allows.
  /// Returns true if retry was scheduled, false if max retries exceeded or disabled.
  ///
  /// If a network monitor is configured and the device is offline, the retry will
  /// trigger immediately when connectivity is restored instead of waiting for the
  /// full backoff delay.
  func attemptRetry(startFromCurrentTime: Bool) async -> Bool {
    // Check if retry is disabled
    if case .disabled = policy {
      logger.debug("Retry disabled, not retrying")
      return false
    }

    // Check if we should retry (e.g., playWhenReady is true)
    guard shouldRetry() else {
      logger.debug("shouldRetry returned false, not retrying")
      return false
    }

    // Check max retries for limited policy
    if case let .limited(maxRetries) = policy {
      if attemptCount >= maxRetries {
        logger.info("Max retries (\(maxRetries)) exceeded, giving up")
        return false
      }
    }

    // Track when we started retrying
    if firstRetryTime == nil {
      firstRetryTime = Date()
    }

    // Check if we've been retrying too long (prevents surprising resumption after long offline periods)
    if let startTime = firstRetryTime {
      let elapsed = Date().timeIntervalSince(startTime)
      if elapsed >= maxRetryDuration {
        logger.info("Max retry duration (\(self.maxRetryDuration)s) exceeded after \(elapsed)s, giving up")
        return false
      }
    }

    // Schedule retry with exponential backoff
    let delaySeconds = calculateDelaySeconds()
    attemptCount += 1
    logger.info("Scheduling retry #\(self.attemptCount) after \(delaySeconds)s")

    // Race between: backoff delay OR network restored (if offline)
    let waitCancelled = await waitForDelayOrNetworkRestored(delaySeconds: delaySeconds)

    if waitCancelled {
      logger.debug("Retry wait cancelled")
      return false
    }

    // Double-check we should still retry after the delay
    guard shouldRetry() else {
      logger.debug("shouldRetry returned false after delay, cancelling retry")
      return false
    }

    // Check duration again after waiting (in case we waited a long time for network)
    if let startTime = firstRetryTime {
      let elapsed = Date().timeIntervalSince(startTime)
      if elapsed >= maxRetryDuration {
        logger.info("Max retry duration (\(self.maxRetryDuration)s) exceeded after waiting (\(elapsed)s), giving up")
        return false
      }
    }

    // Trigger the retry
    logger.info("Executing retry #\(self.attemptCount)")
    onRetry?(startFromCurrentTime)
    return true
  }

  // MARK: - Network-Aware Waiting

  /// Waits for either the backoff delay to elapse OR network to be restored (if offline).
  /// Returns true if cancelled, false if ready to retry.
  private func waitForDelayOrNetworkRestored(delaySeconds: Double) async -> Bool {
    let isOffline = !(networkMonitor?.isOnline ?? true)

    // If online or no monitor, just do a simple sleep
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

    // Race between sleep and network restoration
    return await withTaskGroup(of: Bool.self, returning: Bool.self) { group in
      // Task 1: Sleep for backoff delay
      group.addTask {
        do {
          let nanoseconds = UInt64(delaySeconds * 1_000_000_000)
          try await Task.sleep(nanoseconds: nanoseconds)
          return false // Sleep completed, ready to retry
        } catch {
          return true // Sleep cancelled
        }
      }

      // Task 2: Wait for network restoration
      group.addTask { [weak self] in
        await self?.waitForNetworkRestored(monitor: monitor) ?? true
      }

      // Return when either completes
      let result = await group.next() ?? true
      group.cancelAll()
      return result
    }
  }

  /// Waits until network connectivity is restored.
  /// Returns false when network is restored, true if cancelled.
  @MainActor
  private func waitForNetworkRestored(monitor: NetworkMonitor) async -> Bool {
    // If already online, return immediately
    if monitor.isOnline {
      return false
    }

    isWaitingForNetwork = true

    // Set up listener for network restoration
    let previousHandler = monitor.onChanged

    // Use withCheckedContinuation to wait for network
    let result: Bool = await withCheckedContinuation { continuation in
      var hasResumed = false

      monitor.onChanged = { [weak self] isOnline in
        // Always call previous handler to maintain JS callback
        previousHandler?(isOnline)

        guard !hasResumed else { return }

        if isOnline {
          hasResumed = true
          self?.logger.info("Network restored, accelerating retry")
          // Restore original handler
          monitor.onChanged = previousHandler
          continuation.resume(returning: false) // Network restored, ready to retry
        }
      }
    }

    isWaitingForNetwork = false
    return result
  }
}
