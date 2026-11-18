package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Loader
import java.io.FileNotFoundException
import java.io.IOException

/**
 * What becomes of a recoverable load failure of one media item (ADR 0014). Where media3 resumes the
 * connection in place, it retries after the backoff; anything else ends the connection, for
 * [RetryLoop] to reconnect once the buffer has played out. A preloading item always retries in
 * place. It never gives up on a recoverable failure: the loop's silence deadline does. Called on
 * ExoPlayer's playback thread.
 *
 * @param isCurrent whether the item is the one playing
 * @param onFailure each recoverable failure of the current item, and whether it is retried in place
 */
class RetryLoadErrorHandlingPolicy(
  private val sources: Sources,
  private val isCurrent: () -> Boolean,
  private val cache: Cache? = null,
  private val onFailure: (exception: IOException, inPlace: Boolean) -> Unit,
) : DefaultLoadErrorHandlingPolicy() {

  enum class Sources {
    /** HLS, DASH and SmoothStreaming: segments and playlists retry in place. */
    ADAPTIVE,

    /** In place only once the response declared its length. */
    PROGRESSIVE,

    /** A progressive stream marked live: every failure ends the connection. */
    LIVE,
  }

  override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
    // Not super's verdict: it backs off on some errors it doesn't treat as fatal (a 404), which
    // would retry a dead URL until the silence deadline.
    if (!isRecoverable(loadErrorInfo.exception)) return C.TIME_UNSET
    val backoff = ReconnectPacer.backoffMs(loadErrorInfo.errorCount)
    if (!isCurrent()) return backoff
    val inPlace =
      when (sources) {
        Sources.ADAPTIVE -> true
        Sources.PROGRESSIVE -> hasKnownLength(loadErrorInfo.loadEventInfo)
        Sources.LIVE -> false
      }
    onFailure(loadErrorInfo.exception, inPlace)
    return if (inPlace) backoff else C.TIME_UNSET
  }

  override fun onLoadTaskConcluded(loadTaskId: Long) {
    knownLength -= loadTaskId
  }

  // Remembered per load task: a reopen that fails carries no headers.
  private val knownLength = mutableSetOf<Long>()

  private fun hasKnownLength(info: LoadEventInfo): Boolean {
    if (
      declaresLength(info.responseHeaders) ||
        (info.responseHeaders.isEmpty() && cachedLengthKnown(info.dataSpec))
    )
      knownLength += info.loadTaskId
    return info.loadTaskId in knownLength
  }

  // A load reading from the cache has no response; the cache keeps the length one declared.
  private fun cachedLengthKnown(dataSpec: DataSpec): Boolean {
    val cache = cache ?: return false
    val key = CacheKeyFactory.DEFAULT.buildCacheKey(dataSpec)
    return ContentMetadata.getContentLength(cache.getContentMetadata(key)) !=
      C.LENGTH_UNSET.toLong()
  }

  // The in-place path retries until the loop gives up on it.
  override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE

  private fun declaresLength(headers: Map<String, List<String>>): Boolean =
    headers.any { (name, values) ->
      when {
        name.equals("Content-Length", ignoreCase = true) -> true
        // `bytes 0-99/1234`; a `*` total is a length the server doesn't know.
        name.equals("Content-Range", ignoreCase = true) ->
          values.any { !it.substringAfterLast('/').trim().startsWith('*') }
        else -> false
      }
    }

  // Load errors arrive as raw IOExceptions (a Wi-Fi drop is a socket or DNS error). Anything not
  // known to be fatal is recoverable.
  private fun isRecoverable(exception: IOException): Boolean {
    var current: Throwable? = exception
    while (current != null) {
      when (current) {
        is HttpDataSource.InvalidResponseCodeException ->
          return current.responseCode.let { it == 408 || it == 429 || it in 500..599 }
        // DefaultLoadErrorHandlingPolicy's fatal set.
        is HttpDataSource.CleartextNotPermittedException,
        is ParserException,
        is FileNotFoundException,
        is Loader.UnexpectedLoaderException -> return false
      }
      current = current.cause
    }

    // A position the source no longer serves (behind a live window) won't come back in place.
    return !DataSourceException.isCausedByPositionOutOfRange(exception)
  }
}
