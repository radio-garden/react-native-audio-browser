package com.audiobrowser.browser

import com.audiobrowser.util.awaitNested
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackLoadEvent
import com.margelo.nitro.core.Promise
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Runs [defaultBehavior] when no [handler] is configured. Otherwise awaits the handler and runs
 * [intercepted], even when the handler throws or rejects: the failure is logged, not rethrown.
 */
suspend fun <T> handleTrackLoad(
  handler: ((event: TrackLoadEvent) -> Promise<Promise<Unit>>)?,
  track: Track,
  queue: Array<Track>,
  startIndex: Double,
  startPosition: Double?,
  intercepted: suspend () -> T,
  defaultBehavior: suspend () -> T,
): T {
  if (handler != null) {
    val event = TrackLoadEvent(track, queue, startIndex, startPosition)
    try {
      handler(event).awaitNested()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Timber.e(e, "handleTrackLoad failed")
    }
    return intercepted()
  }
  return defaultBehavior()
}
