package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Loader
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.min
import kotlin.math.pow
import timber.log.Timber

/**
 * Custom LoadErrorHandlingPolicy that retries on recoverable IO errors with exponential backoff.
 *
 * When a network monitor is provided and the device is offline during a network error, the policy
 * will use a shorter retry delay and the network restoration callback can trigger an immediate
 * retry when connectivity is restored.
 *
 * @param maxRetries Maximum number of retries, or null for infinite retries
 * @param maxRetryDurationMs Maximum duration to keep retrying before giving up, or null for default
 *   (2 minutes)
 * @param shouldRetry Optional callback to check if retry should proceed (e.g., check playWhenReady)
 * @param isOnline Optional callback to check current network state
 * @param onRetryPending Optional callback invoked when a retry is pending (for network restoration
 *   acceleration)
 */
class RetryLoadErrorHandlingPolicy(
  private val maxRetries: Int? = null,
  maxRetryDurationMs: Long? = null,
  private val shouldRetry: () -> Boolean = { true },
  private val isOnline: () -> Boolean = { true },
  private val onRetryPending: ((isNetworkError: Boolean) -> Unit)? = null,
) : DefaultLoadErrorHandlingPolicy() {

  companion object {
    private const val INITIAL_RETRY_DELAY_MS = 1000L // 1 second
    private const val MAX_RETRY_DELAY_MS = 5000L // 5 seconds cap
    private const val BACKOFF_MULTIPLIER = 1.5
    // When offline, use a short fixed delay instead of exponential backoff.
    // ExoPlayer's retry timer can't be interrupted, so we use a short delay to:
    // 1. Allow our network restoration callback to trigger immediate retry
    // 2. Keep polling in case the callback doesn't fire
    private const val OFFLINE_RETRY_DELAY_MS = 1000L
    // Default maximum duration to keep retrying before giving up (in milliseconds).
    private const val DEFAULT_MAX_RETRY_DURATION_MS = 120_000L // 2 minutes

    // HTTP status codes that are worth retrying
    private val RETRYABLE_HTTP_STATUS_CODES =
      setOf(
        408, // Request Timeout
        429, // Too Many Requests
        500, // Internal Server Error
        502, // Bad Gateway
        503, // Service Unavailable
        504, // Gateway Timeout
      )
  }

  // Maximum duration to keep retrying before giving up.
  // This prevents surprising playback resumption after long periods offline.
  private val maxRetryDurationMs: Long = maxRetryDurationMs ?: DEFAULT_MAX_RETRY_DURATION_MS

  // Track when we started retrying to enforce max duration
  @Volatile private var firstErrorTime: Long? = null

  /** Resets the retry timer. Call when track changes. */
  fun reset() {
    firstErrorTime = null
  }

  /** Calculates exponential backoff delay: 1s -> 1.5s -> 2.3s -> 3.4s -> 5s (capped) */
  private fun calculateBackoffDelay(errorCount: Int): Long {
    // errorCount is 1-based (first error = 1)
    val exponentialDelay =
      INITIAL_RETRY_DELAY_MS * BACKOFF_MULTIPLIER.pow((errorCount - 1).toDouble())
    return min(exponentialDelay.toLong(), MAX_RETRY_DELAY_MS)
  }

  override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
    val exception = loadErrorInfo.exception
    val errorCount = loadErrorInfo.errorCount
    val currentTime = System.currentTimeMillis()

    // Don't retry if player is paused/stopped (e.g., another app took audio focus)
    if (!shouldRetry()) {
      Timber.d("Retry skipped: shouldRetry() returned false (player likely paused)")
      return C.TIME_UNSET
    }

    // Check if we've exceeded max retries
    if (maxRetries != null && errorCount > maxRetries) {
      Timber.d("Max retries ($maxRetries) exceeded, not retrying")
      return C.TIME_UNSET
    }

    // Track when we started retrying
    if (firstErrorTime == null) {
      firstErrorTime = currentTime
    }

    // Check if we've been retrying too long (prevents surprising resumption after long offline)
    val startTime = firstErrorTime
    if (startTime != null) {
      val elapsed = currentTime - startTime
      if (elapsed >= maxRetryDurationMs) {
        Timber.d(
          "Max retry duration (${maxRetryDurationMs}ms) exceeded after ${elapsed}ms, giving up"
        )
        return C.TIME_UNSET
      }
    }

    // Classify the error
    val errorClassification = classifyError(exception)

    return if (errorClassification.isRecoverable) {
      // Check if we're offline during a network error
      val currentlyOffline = !isOnline()
      val isNetworkError = errorClassification.isNetworkError

      val delay =
        if (currentlyOffline && isNetworkError) {
          // Use shorter delay when offline - we'll retry immediately when network comes back
          Timber.d(
            "Device is offline, using short retry delay (will accelerate when network returns)"
          )
          OFFLINE_RETRY_DELAY_MS
        } else {
          calculateBackoffDelay(errorCount)
        }

      Timber.d(
        "Retrying after IO error (attempt $errorCount, delay ${delay}ms, offline=$currentlyOffline): ${exception.message}"
      )

      // Notify that a retry is pending (for network restoration acceleration)
      onRetryPending?.invoke(isNetworkError)

      delay
    } else {
      // Non-recoverable error: stop now. We can't delegate to super here —
      // DefaultLoadErrorHandlingPolicy still schedules a backoff retry for some errors it doesn't
      // consider fatal (e.g. a 404 InvalidResponseCodeException), which combined with our infinite
      // getMinimumLoadableRetryCount would retry a dead URL until the max-retry-duration timeout.
      C.TIME_UNSET
    }
  }

  /** Classification result for an error */
  private data class ErrorClassification(val isRecoverable: Boolean, val isNetworkError: Boolean)

  /**
   * Classifies whether an error is recoverable and whether it's network-related.
   *
   * Load errors are delivered as a raw [IOException] (e.g.
   * [HttpDataSource.HttpDataSourceException], `UnknownHostException`, `SocketTimeoutException`) —
   * never as a `PlaybackException`, which is only surfaced at the player level *after* retries are
   * exhausted. So we classify by walking the IOException cause chain. This is essential: a plain
   * Wi-Fi drop surfaces as a socket/DNS IOException, and unless we flag it as a network error the
   * connectivity-restoration path can't fire an immediate retry, leaving playback stuck buffering
   * until it returns.
   */
  private fun classifyError(exception: IOException): ErrorClassification {
    val nonRecoverable = ErrorClassification(isRecoverable = false, isNetworkError = false)

    var current: Throwable? = exception
    while (current != null) {
      when (current) {
        // HTTP responses: only retry server errors (5xx) and specific client errors (408, 429).
        // Other status codes (e.g. 403/404) will never succeed on retry.
        is HttpDataSource.InvalidResponseCodeException -> {
          val retryable = current.responseCode in RETRYABLE_HTTP_STATUS_CODES
          return ErrorClassification(isRecoverable = retryable, isNetworkError = retryable)
        }
        // Errors that retrying cannot fix — mirror DefaultLoadErrorHandlingPolicy's
        // non-retryable set so we don't spin until the max-retry-duration timeout.
        is HttpDataSource.CleartextNotPermittedException,
        is ParserException,
        is FileNotFoundException,
        is Loader.UnexpectedLoaderException -> return nonRecoverable
      }
      current = current.cause
    }

    // A read at a position the source can no longer provide (e.g. behind a live window) won't
    // recover via an in-place loader retry.
    if (DataSourceException.isCausedByPositionOutOfRange(exception)) {
      return nonRecoverable
    }

    // Any other IOException is a transient network/transport failure (connection reset, DNS
    // failure, socket timeout, network interface lost, ...): recoverable, and flagged as a network
    // error so connectivity restoration can trigger an immediate retry.
    return ErrorClassification(isRecoverable = true, isNetworkError = true)
  }

  override fun getMinimumLoadableRetryCount(dataType: Int): Int {
    // Return the max retries, or MAX_VALUE for infinite
    return maxRetries ?: Int.MAX_VALUE
  }
}
