package com.audiobrowser.browser

import com.audiobrowser.util.awaitNested
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackLoadEvent
import com.margelo.nitro.core.Promise
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Runs [defaultBehavior] when no [handler] is configured. Otherwise awaits the handler and runs
 * [intercepted], even when the handler throws or rejects (the failure is logged, not rethrown).
 */
suspend fun <T> handleTrackLoad(
  // Nitro wraps a value-returning JS callback in a Promise, so a JS callback returning
  // Promise<void> arrives as Promise<Promise<Unit>>; both layers must be awaited for the JS work
  // to finish.
  handler: ((event: TrackLoadEvent) -> Promise<Promise<Unit>>)?,
  track: Track,
  queue: Array<Track>,
  startIndex: Double,
  intercepted: suspend () -> T,
  defaultBehavior: suspend () -> T,
): T {
  if (handler != null) {
    val event = TrackLoadEvent(track, queue, startIndex)
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
