package com.audiobrowser.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackErrorKind
import java.io.IOException

/**
 * Maps ExoPlayer failures onto [PlaybackErrorKind], the cross-platform kind iOS fills from
 * AVFoundation. A code that names no cause, including `ERROR_CODE_IO_BAD_HTTP_STATUS` with no
 * recoverable status, stays [PlaybackErrorKind.UNKNOWN]: a wrong kind misleads both the listener
 * and the aggregates more than an honest unknown.
 */
object PlaybackErrorClassifier {
  /**
   * Offline wins over everything, then an HTTP status in the cause chain, then the error code.
   *
   * @param online whether the device had connectivity when it failed. ExoPlayer reports a dead
   *   stream and a dead connection with the same codes; only the connectivity monitor tells them
   *   apart.
   */
  fun classify(error: PlaybackException, online: Boolean): PlaybackErrorKind {
    if (!online) return PlaybackErrorKind.OFFLINE
    val responseCode = responseCode(error)
    if (responseCode != null) return kindForHttpStatus(responseCode)
    return kindForErrorCode(error.errorCode)
  }

  /** The HTTP status anywhere in [error]'s cause chain, or null if no HTTP response caused it. */
  fun responseCode(error: PlaybackException): Int? =
    generateSequence(error.cause) { it.cause }
      .filterIsInstance<InvalidResponseCodeException>()
      .firstOrNull()
      ?.responseCode

  /**
   * Classifies a load error the retry policy is still retrying, as a `retrying` [PlaybackError].
   * Load errors arrive as raw [IOException]s, never as [PlaybackException], which exists only once
   * retries are exhausted. [RetryLoadErrorHandlingPolicy] retries only HTTP statuses and transport
   * failures, so any other cause is reported as [PlaybackErrorKind.UNREACHABLE].
   */
  fun retryingLoadError(exception: IOException, online: Boolean): PlaybackError {
    val responseCode =
      generateSequence(exception as Throwable) { it.cause }
        .filterIsInstance<InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode
    // A fixed message per kind, like iOS: raw IOException messages vary between attempts at the
    // same outage, which would defeat the caller's identical-repeat dedupe.
    val (kind, code, message) =
      when {
        !online ->
          Triple(PlaybackErrorKind.OFFLINE, "not-connected-to-internet", "No internet connection")
        responseCode != null ->
          Triple(
            kindForHttpStatus(responseCode),
            "io-bad-http-status",
            "Server responded with HTTP $responseCode",
          )
        else ->
          Triple(
            PlaybackErrorKind.UNREACHABLE,
            "io-network-connection-failed",
            "Could not reach the stream host",
          )
      }
    return PlaybackError(kind, code, message, responseCode?.toDouble(), retrying = true)
  }

  fun kindForHttpStatus(status: Int): PlaybackErrorKind =
    when (status) {
      404,
      410 -> PlaybackErrorKind.NOT_FOUND
      in 500..599 -> PlaybackErrorKind.SERVER_ERROR
      // Every other 4xx (auth, geo-blocking, rate limit) means "you can't have this stream".
      in 400..499 -> PlaybackErrorKind.REJECTED
      else -> PlaybackErrorKind.UNKNOWN
    }

  private fun kindForErrorCode(errorCode: Int): PlaybackErrorKind =
    when (errorCode) {
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
      PlaybackException.ERROR_CODE_TIMEOUT -> PlaybackErrorKind.UNREACHABLE

      PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackErrorKind.NOT_FOUND

      PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
      PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED,
      PlaybackException.ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED,
      PlaybackException.ERROR_CODE_CONCURRENT_STREAM_LIMIT,
      PlaybackException.ERROR_CODE_PARENTAL_CONTROL_RESTRICTED,
      PlaybackException.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
      in DRM_CODES -> PlaybackErrorKind.REJECTED

      PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
      PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
      PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
      PlaybackException.ERROR_CODE_NOT_SUPPORTED,
      in PARSING_CODES,
      in DECODING_CODES,
      in AUDIO_TRACK_CODES -> PlaybackErrorKind.UNPLAYABLE

      else -> PlaybackErrorKind.UNKNOWN
    }

  private val PARSING_CODES =
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException
        .ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
  private val DECODING_CODES =
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException
        .ERROR_CODE_DECODING_RESOURCES_RECLAIMED
  private val AUDIO_TRACK_CODES =
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED..PlaybackException
        .ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED
  private val DRM_CODES =
    PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED
}
