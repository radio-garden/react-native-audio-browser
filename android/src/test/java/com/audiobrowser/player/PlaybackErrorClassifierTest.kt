package com.audiobrowser.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import com.margelo.nitro.audiobrowser.PlaybackErrorKind
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric: building the DataSpec that InvalidResponseCodeException requires goes through
// android.net.Uri, which is unimplemented on a bare JVM.
@RunWith(RobolectricTestRunner::class)
class PlaybackErrorClassifierTest {
  private fun exception(errorCode: Int, cause: Throwable? = null) =
    PlaybackException("test", cause, errorCode)

  private fun httpException(responseCode: Int) =
    InvalidResponseCodeException(
      responseCode,
      null,
      IOException("bad status"),
      emptyMap(),
      DataSpec.Builder().setUri("https://example.test/stream").build(),
      ByteArray(0),
    )

  // MARK: - Offline

  /**
   * ExoPlayer reports a dead radio and a dead wifi connection with the same codes, so an offline
   * device must win over whatever the code says — this is the one classification the error itself
   * can never carry.
   */
  @Test
  fun `offline device outranks the error code`() {
    val error = exception(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpException(404))
    assertEquals(PlaybackErrorKind.OFFLINE, PlaybackErrorClassifier.classify(error, online = false))
  }

  // MARK: - HTTP status

  @Test
  fun `http status is recovered from the cause chain`() {
    val error = exception(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpException(503))
    assertEquals(503, PlaybackErrorClassifier.responseCode(error))
    assertEquals(
      PlaybackErrorKind.SERVER_ERROR,
      PlaybackErrorClassifier.classify(error, online = true),
    )
  }

  /** media3 nests the response exception under wrapper exceptions rather than as a direct cause. */
  @Test
  fun `http status is recovered when nested deeper in the chain`() {
    val nested = IOException("loading failed", httpException(404))
    val error = exception(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, nested)
    assertEquals(PlaybackErrorKind.NOT_FOUND, PlaybackErrorClassifier.classify(error, online = true))
  }

  @Test
  fun `no response code when the failure was not an http response`() {
    val error = exception(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
    assertNull(PlaybackErrorClassifier.responseCode(error))
  }

  @Test
  fun `http statuses map to kinds`() {
    val expectations =
      listOf(
        404 to PlaybackErrorKind.NOT_FOUND,
        410 to PlaybackErrorKind.NOT_FOUND,
        401 to PlaybackErrorKind.REJECTED,
        403 to PlaybackErrorKind.REJECTED,
        429 to PlaybackErrorKind.REJECTED,
        500 to PlaybackErrorKind.SERVER_ERROR,
        503 to PlaybackErrorKind.SERVER_ERROR,
        302 to PlaybackErrorKind.UNKNOWN,
      )
    for ((status, expected) in expectations) {
      assertEquals("HTTP $status", expected, PlaybackErrorClassifier.kindForHttpStatus(status))
    }
  }

  /**
   * A recovered status must win over the code-based mapping: `ERROR_CODE_IO_BAD_HTTP_STATUS` alone
   * says only "non-2xx", which would land in UNKNOWN and lose the dead-station signal.
   */
  @Test
  fun `recovered status outranks the error code mapping`() {
    val error = exception(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpException(404))
    assertEquals(PlaybackErrorKind.NOT_FOUND, PlaybackErrorClassifier.classify(error, online = true))
  }

  // MARK: - Error codes

  @Test
  fun `error codes map to kinds`() {
    val expectations =
      listOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED to PlaybackErrorKind.UNREACHABLE,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT to PlaybackErrorKind.UNREACHABLE,
        PlaybackException.ERROR_CODE_TIMEOUT to PlaybackErrorKind.UNREACHABLE,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND to PlaybackErrorKind.NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION to PlaybackErrorKind.REJECTED,
        PlaybackException.ERROR_CODE_NOT_AVAILABLE_IN_REGION to PlaybackErrorKind.REJECTED,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED to PlaybackErrorKind.REJECTED,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE to PlaybackErrorKind.UNPLAYABLE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED to PlaybackErrorKind.UNPLAYABLE,
        PlaybackException.ERROR_CODE_DECODING_FAILED to PlaybackErrorKind.UNPLAYABLE,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED to PlaybackErrorKind.UNPLAYABLE,
      )
    for ((code, expected) in expectations) {
      assertEquals(
        "error code $code",
        expected,
        PlaybackErrorClassifier.classify(exception(code), online = true),
      )
    }
  }

  /**
   * Codes that name no cause must stay UNKNOWN. Guessing them into a friendlier bucket would put a
   * wrong line on the listener's screen and poison the telemetry aggregates that motivated the
   * classification in the first place.
   */
  @Test
  fun `codes without evidence stay unknown`() {
    val unevidenced =
      listOf(
        PlaybackException.ERROR_CODE_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_REMOTE_ERROR,
        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
      )
    for (code in unevidenced) {
      assertEquals(
        "error code $code",
        PlaybackErrorKind.UNKNOWN,
        PlaybackErrorClassifier.classify(exception(code), online = true),
      )
    }
  }
}
