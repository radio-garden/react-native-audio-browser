package com.audiobrowser.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackErrorKind
import java.io.IOException

/**
 * Maps ExoPlayer's failure codes onto [PlaybackErrorKind], the cross-platform contract iOS
 * populates from AVFoundation. Consumers branch on the kind; the raw ExoPlayer code still travels
 * alongside it as `PlaybackError.code` for telemetry.
 *
 * Codes that carry no real evidence of a cause (`ERROR_CODE_UNSPECIFIED`,
 * `ERROR_CODE_IO_UNSPECIFIED`, a bad HTTP status whose response code we could not recover) stay
 * [PlaybackErrorKind.UNKNOWN] rather than being guessed into a nicer-sounding bucket — a wrong
 * classification is worse than an honest one, both for the listener and for the aggregates.
 */
object PlaybackErrorClassifier {
  /**
   * Classifies a player failure.
   *
   * @param online whether the device had connectivity at the moment of failure. ExoPlayer's codes
   *   cannot distinguish "the device is offline" from "this stream is unreachable"; only the
   *   connectivity monitor can, which is why it is passed in.
   */
  fun classify(error: PlaybackException, online: Boolean): PlaybackErrorKind {
    if (!online) return PlaybackErrorKind.OFFLINE
    val responseCode = responseCode(error)
    if (responseCode != null) return kindForHttpStatus(responseCode)
    return kindForErrorCode(error.errorCode)
  }

  /** The HTTP status the server answered with, if this failure came from an HTTP response. */
  fun responseCode(error: PlaybackException): Int? =
    generateSequence(error.cause) { it.cause }
      .filterIsInstance<InvalidResponseCodeException>()
      .firstOrNull()
      ?.responseCode

  /**
   * Classifies a load error the retry policy is still working on, as an advisory (`retrying`)
   * [PlaybackError]. Load errors arrive as raw [IOException]s (see
   * [RetryLoadErrorHandlingPolicy.classifyError]), never as [PlaybackException] — that shape only
   * exists once retries are exhausted. The policy only retries HTTP statuses and transport
   * failures, so a non-HTTP retryable load error is by construction a network one.
   */
  fun retryingLoadError(exception: IOException, online: Boolean): PlaybackError {
    val responseCode =
      generateSequence(exception as Throwable) { it.cause }
        .filterIsInstance<InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode
    // Fixed message per classification (mirrors iOS's localized descriptions): raw
    // IOException messages vary between attempts of the same outage, which would defeat
    // the caller's identical-repeat dedupe and churn the wire payload.
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
      // Every other 4xx is the server refusing us — auth, geo-blocking, a rate limit. All of them
      // mean "you can't have this stream", not "retry".
      in 400..499 -> PlaybackErrorKind.REJECTED
      else -> PlaybackErrorKind.UNKNOWN
    }

  private fun kindForErrorCode(errorCode: Int): PlaybackErrorKind =
    when (errorCode) {
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
      PlaybackException.ERROR_CODE_TIMEOUT -> PlaybackErrorKind.UNREACHABLE

      PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackErrorKind.NOT_FOUND

      // The server, the licence server, or the session refused us.
      PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
      PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED,
      PlaybackException.ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED,
      PlaybackException.ERROR_CODE_CONCURRENT_STREAM_LIMIT,
      PlaybackException.ERROR_CODE_PARENTAL_CONTROL_RESTRICTED,
      PlaybackException.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
      in DRM_CODES -> PlaybackErrorKind.REJECTED

      // Fetched, but nothing we can turn into audio: wrong content type, malformed or unsupported
      // container, a decoder that refused it, or an output track we could not open.
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
