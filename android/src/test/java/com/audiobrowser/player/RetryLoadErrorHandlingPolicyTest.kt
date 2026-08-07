package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Loader
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class RetryLoadErrorHandlingPolicyTest {
  // LoadEventInfo requires a DataSpec (Android Uri) to construct, so mock it.
  // MediaLoadData has a dependency-free constructor. The policy only ever reads
  // LoadErrorInfo.exception and LoadErrorInfo.errorCount.
  private val loadEventInfo = mock(LoadEventInfo::class.java)
  private val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

  private fun errorInfo(exception: IOException, errorCount: Int = 1) =
    LoadErrorHandlingPolicy.LoadErrorInfo(loadEventInfo, mediaLoadData, exception, errorCount)

  /** Builds an [HttpDataSource.InvalidResponseCodeException] for the given HTTP status code. */
  private fun httpError(responseCode: Int): HttpDataSource.InvalidResponseCodeException =
    HttpDataSource.InvalidResponseCodeException(
      responseCode,
      "response message",
      /* cause= */ null,
      emptyMap(),
      mock(DataSpec::class.java),
      ByteArray(0),
    )

  /**
   * Asserts the given error is classified non-recoverable: the default policy gives up (no retry is
   * scheduled) and the network-retry callback is never armed.
   */
  private fun assertNonRecoverable(exception: IOException) {
    var pendingNetwork: Boolean? = null
    val policy =
      RetryLoadErrorHandlingPolicy(
        onRetryPending = { _, isNetworkError -> pendingNetwork = isNetworkError }
      )
    val delay = policy.getRetryDelayMsFor(errorInfo(exception))
    assertEquals(C.TIME_UNSET, delay)
    assertNull(pendingNetwork)
  }

  @Test
  fun `offline network IOException is retried and arms the network-retry callback`() {
    var pendingNetwork: Boolean? = null
    val policy =
      RetryLoadErrorHandlingPolicy(
        maxRetries = null,
        isOnline = { false },
        onRetryPending = { _, isNetworkError -> pendingNetwork = isNetworkError },
      )

    // A wifi drop surfaces as a raw IOException from the socket/DNS layer, NOT a
    // PlaybackException. This must still be treated as a recoverable network error.
    val delay = policy.getRetryDelayMsFor(errorInfo(UnknownHostException("Unable to resolve host")))

    // Recoverable -> a real (non-TIME_UNSET) retry delay is scheduled.
    assertNotEquals(C.TIME_UNSET, delay)
    // While offline we use the short fixed delay so reconnect can accelerate.
    assertEquals(1000L, delay)
    // The network-retry callback must be armed so connectivity restoration can
    // trigger an immediate exoPlayer.prepare().
    assertEquals(true, pendingNetwork)
  }

  @Test
  fun `socket timeout is classified as a recoverable network error`() {
    var pendingNetwork: Boolean? = null
    val policy =
      RetryLoadErrorHandlingPolicy(
        isOnline = { true },
        onRetryPending = { _, isNetworkError -> pendingNetwork = isNetworkError },
      )

    val delay = policy.getRetryDelayMsFor(errorInfo(SocketTimeoutException("timeout")))

    assertNotEquals(C.TIME_UNSET, delay)
    assertTrue("expected a positive retry delay, got $delay", delay > 0)
    assertEquals(true, pendingNetwork)
  }

  @Test
  fun `retry is skipped when shouldRetry is false`() {
    val policy = RetryLoadErrorHandlingPolicy(shouldRetry = { false })
    val delay = policy.getRetryDelayMsFor(errorInfo(UnknownHostException("offline")))
    assertEquals(C.TIME_UNSET, delay)
  }

  @Test
  fun `retry stops once max retries is exceeded`() {
    val policy = RetryLoadErrorHandlingPolicy(maxRetries = 3)
    val delay =
      policy.getRetryDelayMsFor(errorInfo(UnknownHostException("offline"), errorCount = 4))
    assertEquals(C.TIME_UNSET, delay)
  }

  @Test
  fun `retryable HTTP status (503) is recoverable and arms the network-retry callback`() {
    var pendingNetwork: Boolean? = null
    val policy =
      RetryLoadErrorHandlingPolicy(
        isOnline = { true },
        onRetryPending = { _, isNetworkError -> pendingNetwork = isNetworkError },
      )

    val delay = policy.getRetryDelayMsFor(errorInfo(httpError(503)))

    assertNotEquals(C.TIME_UNSET, delay)
    assertEquals(true, pendingNetwork)
  }

  @Test
  fun `non-retryable HTTP status (404) is not retried`() {
    var pendingNetwork: Boolean? = null
    val policy =
      RetryLoadErrorHandlingPolicy(
        onRetryPending = { _, isNetworkError -> pendingNetwork = isNetworkError }
      )

    val delay = policy.getRetryDelayMsFor(errorInfo(httpError(404)))

    // A 404 will never succeed on retry: stop immediately rather than spinning until the
    // max-retry-duration timeout, and never arm the connectivity-restoration path.
    assertEquals(C.TIME_UNSET, delay)
    assertNull(pendingNetwork)
  }

  @Test
  fun `parser errors are non-recoverable`() {
    assertNonRecoverable(ParserException.createForMalformedContainer("bad container", null))
  }

  @Test
  fun `file-not-found errors are non-recoverable`() {
    assertNonRecoverable(FileNotFoundException("missing"))
  }

  @Test
  fun `unexpected loader errors are non-recoverable`() {
    assertNonRecoverable(Loader.UnexpectedLoaderException(IllegalStateException("boom")))
  }

  @Test
  @Suppress("DEPRECATION") // DataSourceException(reason) is the only way to build this in a test
  fun `reads past the available position are non-recoverable`() {
    assertNonRecoverable(DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE))
  }
}
