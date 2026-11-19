package com.audiobrowser.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Loader
import com.audiobrowser.player.RetryLoadErrorHandlingPolicy.Sources
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RetryLoadErrorHandlingPolicyTest {
  private val failures = mutableListOf<Boolean>()
  private var current = true

  private fun policy(sources: Sources, cache: Cache? = null) =
    RetryLoadErrorHandlingPolicy(sources, { current }, cache) { _, inPlace -> failures += inPlace }

  private fun errorInfo(
    exception: IOException = UnknownHostException("dead host"),
    errorCount: Int = 1,
    headers: Map<String, List<String>> = emptyMap(),
    loadTaskId: Long = 1,
  ) =
    LoadErrorHandlingPolicy.LoadErrorInfo(
      LoadEventInfo(
        loadTaskId,
        DataSpec(Uri.parse("https://example.com/stream")),
        Uri.parse("https://example.com/stream"),
        headers,
        /* elapsedRealtimeMs= */ 0,
        /* loadDurationMs= */ 0,
        /* bytesLoaded= */ 0,
      ),
      MediaLoadData(C.DATA_TYPE_MEDIA),
      exception,
      errorCount,
    )

  private fun httpError(responseCode: Int) =
    HttpDataSource.InvalidResponseCodeException(
      responseCode,
      "response message",
      /* cause= */ null,
      emptyMap(),
      mock(DataSpec::class.java),
      ByteArray(0),
    )

  @Test
  fun `an adaptive stream retries in place with the backoff, without end`() {
    val policy = policy(Sources.ADAPTIVE)

    val delays = (1..8).map { policy.getRetryDelayMsFor(errorInfo(errorCount = it)) }

    assertEquals(listOf(1000L, 1500, 2250, 3375, 5000, 5000, 5000, 5000), delays)
    assertEquals(List(8) { true }, failures)
  }

  @Test
  fun `a live stream's failure ends the connection for the loop`() {
    assertEquals(C.TIME_UNSET, policy(Sources.LIVE).getRetryDelayMsFor(errorInfo()))
    assertEquals(listOf(false), failures)
  }

  @Test
  fun `a progressive response of known length retries in place`() {
    val policy = policy(Sources.PROGRESSIVE)

    val length = errorInfo(headers = mapOf("Content-Length" to listOf("1234")))
    val range = errorInfo(headers = mapOf("content-range" to listOf("bytes 0-99/1234")))

    assertEquals(1000L, policy.getRetryDelayMsFor(length))
    assertEquals(1000L, policy.getRetryDelayMsFor(range))
    assertEquals(listOf(true, true), failures)
  }

  @Test
  fun `a file of known length stays in place when a reopen fails without headers`() {
    val policy = policy(Sources.PROGRESSIVE)

    policy.getRetryDelayMsFor(errorInfo(headers = mapOf("Content-Length" to listOf("1234"))))
    val reopen = policy.getRetryDelayMsFor(errorInfo(errorCount = 2))
    val another = policy.getRetryDelayMsFor(errorInfo(loadTaskId = 2))

    assertEquals(1500L, reopen)
    assertEquals(C.TIME_UNSET, another)
    assertEquals(listOf(true, true, false), failures)
  }

  @Test
  fun `a file of known length read from the cache stays in place when the switch upstream fails`() {
    val cache =
      SimpleCache(
        Files.createTempDirectory("cache").toFile(),
        NoOpCacheEvictor(),
        StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()),
      )
    try {
      cache.applyContentMetadataMutations(
        "https://example.com/stream",
        ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, 1234) },
      )
      val policy = policy(Sources.PROGRESSIVE, cache)

      assertEquals(1000L, policy.getRetryDelayMsFor(errorInfo()))
      assertEquals(listOf(true), failures)
    } finally {
      cache.release()
    }
  }

  @Test
  fun `an item that only preloads retries in place and reports nothing`() {
    current = false

    assertEquals(1000L, policy(Sources.LIVE).getRetryDelayMsFor(errorInfo()))
    assertTrue(failures.isEmpty())
  }

  @Test
  fun `a progressive response of unknown length ends the connection`() {
    val policy = policy(Sources.PROGRESSIVE)

    val none = errorInfo(SocketTimeoutException("timeout"))
    val unknownTotal = errorInfo(headers = mapOf("Content-Range" to listOf("bytes 0-99/*")))

    assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(none))
    assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(unknownTotal))
    assertEquals(listOf(false, false), failures)
  }

  @Test
  fun `a retryable HTTP status (503) is recoverable`() {
    assertEquals(1000L, policy(Sources.ADAPTIVE).getRetryDelayMsFor(errorInfo(httpError(503))))
    assertEquals(listOf(true), failures)
  }

  @Test
  fun `media3 never gives up on a loadable itself`() {
    assertEquals(
      Int.MAX_VALUE,
      policy(Sources.ADAPTIVE).getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA),
    )
  }

  @Test
  fun `fatal errors surface and reach no one`() {
    @Suppress("DEPRECATION") // DataSourceException(reason) is the only way to build this in a test
    val fatal =
      listOf(
        httpError(404),
        ParserException.createForMalformedContainer("bad container", null),
        FileNotFoundException("missing"),
        Loader.UnexpectedLoaderException(IllegalStateException("boom")),
        DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE),
      )
    for (sources in Sources.entries) {
      for (exception in fatal) {
        assertEquals(
          "$sources: $exception",
          C.TIME_UNSET,
          policy(sources).getRetryDelayMsFor(errorInfo(exception)),
        )
      }
    }
    assertTrue(failures.isEmpty())
  }
}
