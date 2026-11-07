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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class RetryLoadErrorHandlingPolicyTest {
  // LoadEventInfo needs an Android Uri to construct; the policy reads only the exception and the
  // error count.
  private val loadEventInfo = mock(LoadEventInfo::class.java)
  private val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

  private fun errorInfo(exception: IOException, errorCount: Int = 1) =
    LoadErrorHandlingPolicy.LoadErrorInfo(loadEventInfo, mediaLoadData, exception, errorCount)

  private fun httpError(responseCode: Int): HttpDataSource.InvalidResponseCodeException =
    HttpDataSource.InvalidResponseCodeException(
      responseCode,
      "response message",
      /* cause= */ null,
      emptyMap(),
      mock(DataSpec::class.java),
      ByteArray(0),
    )

  private fun assertNonRecoverable(exception: IOException) {
    var retryPending = false
    val policy = RetryLoadErrorHandlingPolicy(onRetryPending = { retryPending = true })
    val delay = policy.getRetryDelayMsFor(errorInfo(exception))
    assertEquals(C.TIME_UNSET, delay)
    assertFalse(retryPending)
  }

  @Test
  fun `offline network IOException is retried and reports the pending retry`() {
    var retryPending = false
    val policy =
      RetryLoadErrorHandlingPolicy(
        maxRetries = null,
        isOnline = { false },
        onRetryPending = { retryPending = true },
      )

    val delay = policy.getRetryDelayMsFor(errorInfo(UnknownHostException("Unable to resolve host")))

    assertNotEquals(C.TIME_UNSET, delay)
    // The offline delay.
    assertEquals(1000L, delay)
    assertTrue(retryPending)
  }

  @Test
  fun `socket timeout is classified as a recoverable network error`() {
    var retryPending = false
    val policy =
      RetryLoadErrorHandlingPolicy(isOnline = { true }, onRetryPending = { retryPending = true })

    val delay = policy.getRetryDelayMsFor(errorInfo(SocketTimeoutException("timeout")))

    assertNotEquals(C.TIME_UNSET, delay)
    assertTrue("expected a positive retry delay, got $delay", delay > 0)
    assertTrue(retryPending)
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
  fun `retryable HTTP status (503) is recoverable and reports the pending retry`() {
    var retryPending = false
    val policy =
      RetryLoadErrorHandlingPolicy(isOnline = { true }, onRetryPending = { retryPending = true })

    val delay = policy.getRetryDelayMsFor(errorInfo(httpError(503)))

    assertNotEquals(C.TIME_UNSET, delay)
    assertTrue(retryPending)
  }

  @Test
  fun `non-retryable HTTP status (404) is not retried`() {
    var retryPending = false
    val policy = RetryLoadErrorHandlingPolicy(onRetryPending = { retryPending = true })

    val delay = policy.getRetryDelayMsFor(errorInfo(httpError(404)))

    assertEquals(C.TIME_UNSET, delay)
    assertFalse(retryPending)
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

/** Clock-based: 100ms budgets and 150ms sleeps between errors leave margin both ways. */
class RetryLoadErrorHandlingPolicyBudgetTest {
  private val loadEventInfo = org.mockito.Mockito.mock(LoadEventInfo::class.java)
  private val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

  private fun errorInfo(errorCount: Int = 1) =
    LoadErrorHandlingPolicy.LoadErrorInfo(
      loadEventInfo,
      mediaLoadData,
      UnknownHostException("dead host"),
      errorCount,
    )

  @Test
  fun `never-played load gives up after the first-connect budget`() {
    val policy =
      RetryLoadErrorHandlingPolicy(firstConnectMaxRetryDurationMs = 100, hasPlayed = { false })

    assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo()))
    Thread.sleep(150)
    assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(errorCount = 2)))
  }

  @Test
  fun `played load ignores the first-connect budget`() {
    val policy =
      RetryLoadErrorHandlingPolicy(firstConnectMaxRetryDurationMs = 100, hasPlayed = { true })

    assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo()))
    Thread.sleep(150)
    assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(errorCount = 2)))
  }

  @Test
  fun `offline time does not burn the first-connect budget`() {
    var online = false
    val policy =
      RetryLoadErrorHandlingPolicy(
        firstConnectMaxRetryDurationMs = 100,
        isOnline = { online },
        hasPlayed = { false },
      )

    assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo()))
    Thread.sleep(150)
    assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(errorCount = 2)))

    online = true
    assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(errorCount = 3)))
    Thread.sleep(150)
    assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(errorCount = 4)))
  }

  @Test
  fun `offline stretch mid-budget restarts the first-connect clock`() {
    var online = true
    val policy =
      RetryLoadErrorHandlingPolicy(
        firstConnectMaxRetryDurationMs = 100,
        isOnline = { online },
        hasPlayed = { false },
      )

    assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo()))
    Thread.sleep(150)
    online = false
    assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(errorCount = 2)))

    online = true
    assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(errorCount = 3)))
    Thread.sleep(150)
    assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(errorCount = 4)))
  }

  @Test
  fun `reset restarts the first-connect clock`() {
    val policy =
      RetryLoadErrorHandlingPolicy(firstConnectMaxRetryDurationMs = 100, hasPlayed = { false })

    assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo()))
    Thread.sleep(150)
    policy.reset()
    assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo()))
  }
}
