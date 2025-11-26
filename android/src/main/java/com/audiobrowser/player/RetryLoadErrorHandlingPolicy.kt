package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlin.math.min
import kotlin.math.pow
import timber.log.Timber

/**
 * Custom LoadErrorHandlingPolicy that retries on recoverable IO errors with exponential backoff.
 *
 * @param maxRetries Maximum number of retries, or null for infinite retries
 * @param shouldRetry Optional callback to check if retry should proceed (e.g., check playWhenReady)
 */
class RetryLoadErrorHandlingPolicy(
  private val maxRetries: Int? = null,
  private val shouldRetry: () -> Boolean = { true },
) : DefaultLoadErrorHandlingPolicy() {

  companion object {
    private const val INITIAL_RETRY_DELAY_MS = 1000L // 1 second
    private const val MAX_RETRY_DELAY_MS = 5000L // 5 seconds cap
    private const val BACKOFF_MULTIPLIER = 1.5

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

  /** Calculates exponential backoff delay: 1s -> 1.5s -> 2.3s -> 3.4s -> 5s (capped) */
  private fun calculateBackoffDelay(errorCount: Int): Long {
    // errorCount is 1-based (first error = 1)
    val exponentialDelay =
      INITIAL_RETRY_DELAY_MS * BACKOFF_MULTIPLIER.pow((errorCount - 1).toDouble())
    return min(exponentialDelay.toLong(), MAX_RETRY_DELAY_MS)
  }

  /**
   * Checks if an HTTP error is worth retrying based on status code. Only server errors (5xx) and
   * specific client errors (408, 429) are retryable.
   */
  private fun isRetryableHttpError(exception: Throwable): Boolean {
    // Check the exception chain for HttpDataSource.InvalidResponseCodeException
    var current: Throwable? = exception
    while (current != null) {
      if (current is HttpDataSource.InvalidResponseCodeException) {
        return current.responseCode in RETRYABLE_HTTP_STATUS_CODES
      }
      current = current.cause
    }
    return false
  }

  override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
    val exception = loadErrorInfo.exception
    val errorCount = loadErrorInfo.errorCount

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

    // For recoverable IO errors, return retry delay with exponential backoff
    val isRecoverableError =
      when {
        exception is PlaybackException -> {
          when (exception.errorCode) {
            // Network errors are always retryable
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> true
            // HTTP errors need status code inspection
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> isRetryableHttpError(exception)
            // Unspecified IO errors might be transient
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true
            // These are permanent failures, don't retry:
            // - ERROR_CODE_IO_FILE_NOT_FOUND (404)
            // - ERROR_CODE_IO_NO_PERMISSION (403)
            // - ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED (config issue)
            // - ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE (seek issue)
            else -> false
          }
        }
        // Catch IOExceptions that haven't been wrapped in PlaybackException yet
        // Check for retryable HTTP errors first
        isRetryableHttpError(exception) -> true
        // Generic IOExceptions (not HTTP-related) are likely network issues
        exception is java.io.IOException || exception.cause is java.io.IOException -> {
          // But not if it's an HTTP error we already determined isn't retryable
          exception !is HttpDataSource.InvalidResponseCodeException
        }
        else -> false
      }

    return if (isRecoverableError) {
      val delay = calculateBackoffDelay(errorCount)
      Timber.d(
        "Retrying after IO error (attempt $errorCount, delay ${delay}ms): ${exception.message}"
      )
      delay
    } else {
      // For non-recoverable errors, use default behavior (no retry)
      super.getRetryDelayMsFor(loadErrorInfo)
    }
  }

  override fun getMinimumLoadableRetryCount(dataType: Int): Int {
    // Return the max retries, or MAX_VALUE for infinite
    return maxRetries ?: Int.MAX_VALUE
  }
}
