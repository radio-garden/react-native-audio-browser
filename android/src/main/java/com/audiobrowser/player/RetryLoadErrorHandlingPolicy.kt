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
 * Retries recoverable load errors with exponential backoff, until one of two duration budgets runs
 * out.
 *
 * A load that has never produced audio ([hasPlayed]) is usually a dead stream, so it gets the short
 * first-connect budget, counted in online time only, so a station tapped while offline still gets
 * its seconds once connectivity returns. Every load is also bounded by the full recovery budget,
 * offline time included. Budgets are kept here, never through
 * [LoadErrorHandlingPolicy.LoadErrorInfo.errorCount]: that counts per loadable (an HLS stream has a
 * playlist and segment loadables, each counted apart), so it cannot bound a load.
 *
 * @param maxRetries the most retries per loadable; null for no limit
 * @param maxRetryDurationMs the full recovery budget; null for 2 minutes
 * @param firstConnectMaxRetryDurationMs the online budget of a load that has never produced audio;
 *   null for 12 seconds
 * @param shouldRetry false gives up at once (e.g. the player is paused)
 * @param hasPlayed whether the current load has produced audio; called on ExoPlayer's loader thread
 * @param onRetryPending called on ExoPlayer's loader thread each time a retry is scheduled, with
 *   the error being retried
 */
class RetryLoadErrorHandlingPolicy(
  private val maxRetries: Int? = null,
  maxRetryDurationMs: Long? = null,
  firstConnectMaxRetryDurationMs: Long? = null,
  private val shouldRetry: () -> Boolean = { true },
  private val isOnline: () -> Boolean = { true },
  private val hasPlayed: () -> Boolean = { false },
  private val onRetryPending: ((exception: IOException) -> Unit)? = null,
) : DefaultLoadErrorHandlingPolicy() {

  companion object {
    private const val INITIAL_RETRY_DELAY_MS = 1000L
    private const val MAX_RETRY_DELAY_MS = 5000L
    private const val BACKOFF_MULTIPLIER = 1.5
    // Fixed while offline, so the first retry after connectivity returns lands soon.
    private const val OFFLINE_RETRY_DELAY_MS = 1000L
    private const val DEFAULT_MAX_RETRY_DURATION_MS = 120_000L
    private const val DEFAULT_FIRST_CONNECT_MAX_RETRY_DURATION_MS = 12_000L

    private val RETRYABLE_HTTP_STATUS_CODES = setOf(408, 429, 500, 502, 503, 504)
  }

  // Stops playback resuming by surprise after a long time offline.
  private val maxRetryDurationMs: Long = maxRetryDurationMs ?: DEFAULT_MAX_RETRY_DURATION_MS

  private val firstConnectMaxRetryDurationMs: Long =
    firstConnectMaxRetryDurationMs ?: DEFAULT_FIRST_CONNECT_MAX_RETRY_DURATION_MS

  // Written on ExoPlayer's loader thread, reset from the main thread.
  @Volatile private var firstErrorTime: Long? = null

  // The first-connect budget's clock: set at the first error seen online and wiped by any seen
  // offline, so an offline stretch never burns it.
  @Volatile private var firstOnlineErrorTime: Long? = null

  /** Restarts both budgets: call when a new load begins, or once playback has proven healthy. */
  fun reset() {
    firstErrorTime = null
    firstOnlineErrorTime = null
  }

  /** The name of the exhausted budget, or null while both have time left. */
  private fun exhaustedBudget(currentTime: Long): String? {
    firstErrorTime?.let { start ->
      if (currentTime - start >= maxRetryDurationMs)
        return "max retry duration ($maxRetryDurationMs ms)"
    }
    if (!hasPlayed() && isOnline()) {
      firstOnlineErrorTime?.let { start ->
        if (currentTime - start >= firstConnectMaxRetryDurationMs) {
          return "first-connect retry duration ($firstConnectMaxRetryDurationMs ms)"
        }
      }
    }
    return null
  }

  /** 1s, 1.5s, 2.25s, ... capped at [MAX_RETRY_DELAY_MS]; [errorCount] is 1-based. */
  private fun calculateBackoffDelay(errorCount: Int): Long {
    val exponentialDelay =
      INITIAL_RETRY_DELAY_MS * BACKOFF_MULTIPLIER.pow((errorCount - 1).toDouble())
    return min(exponentialDelay.toLong(), MAX_RETRY_DELAY_MS)
  }

  override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
    val exception = loadErrorInfo.exception
    val errorCount = loadErrorInfo.errorCount
    val currentTime = System.currentTimeMillis()

    if (!shouldRetry()) {
      Timber.d("Retry skipped: shouldRetry() returned false (player likely paused)")
      return C.TIME_UNSET
    }

    if (maxRetries != null && errorCount > maxRetries) {
      Timber.d("Max retries ($maxRetries) exceeded, not retrying")
      return C.TIME_UNSET
    }

    if (firstErrorTime == null) {
      firstErrorTime = currentTime
    }
    if (isOnline()) {
      if (firstOnlineErrorTime == null) firstOnlineErrorTime = currentTime
    } else {
      firstOnlineErrorTime = null
    }

    exhaustedBudget(currentTime)?.let { budget ->
      Timber.d("$budget exceeded, giving up")
      return C.TIME_UNSET
    }

    return if (isRecoverable(exception)) {
      val currentlyOffline = !isOnline()

      val delay =
        if (currentlyOffline) {
          Timber.d("Device is offline, using short retry delay")
          OFFLINE_RETRY_DELAY_MS
        } else {
          calculateBackoffDelay(errorCount)
        }

      Timber.d(
        "Retrying after IO error (attempt $errorCount, delay ${delay}ms, offline=$currentlyOffline): ${exception.message}"
      )

      onRetryPending?.invoke(exception)

      delay
    } else {
      // Not super's verdict: it still backs off on some errors it doesn't treat as fatal (a 404),
      // which with the unbounded getMinimumLoadableRetryCount would retry a dead URL for the whole
      // budget.
      C.TIME_UNSET
    }
  }

  /**
   * Walks the cause chain: load errors arrive as raw IOExceptions (a Wi-Fi drop is a socket or DNS
   * error), never as a PlaybackException. Anything not known to be fatal is recoverable.
   */
  private fun isRecoverable(exception: IOException): Boolean {
    var current: Throwable? = exception
    while (current != null) {
      when (current) {
        is HttpDataSource.InvalidResponseCodeException ->
          return current.responseCode in RETRYABLE_HTTP_STATUS_CODES
        // Mirrors DefaultLoadErrorHandlingPolicy's fatal set.
        is HttpDataSource.CleartextNotPermittedException,
        is ParserException,
        is FileNotFoundException,
        is Loader.UnexpectedLoaderException -> return false
      }
      current = current.cause
    }

    // A position the source can no longer serve (e.g. behind a live window) won't come back on an
    // in-place retry.
    return !DataSourceException.isCausedByPositionOutOfRange(exception)
  }

  override fun getMinimumLoadableRetryCount(dataType: Int): Int {
    // Unbounded by default: the duration budgets decide when to stop.
    return maxRetries ?: Int.MAX_VALUE
  }
}
