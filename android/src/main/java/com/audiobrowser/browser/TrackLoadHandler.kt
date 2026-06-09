package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackLoadEvent
import com.margelo.nitro.core.Promise
import timber.log.Timber

/**
 * Handles track load interception using the double-Promise pattern required by Nitro.
 *
 * If the handler is present, awaits it and returns [intercepted]. Otherwise runs [default].
 *
 * Nitro wraps value-returning JS callbacks in Promise<T> for thread safety, so a JS callback
 * returning Promise<void> becomes Promise<Promise<Unit>> on the native side. Both layers must be
 * awaited to properly wait for the JS work to complete.
 *
 * @param handler The handleTrackLoad callback from NativeBrowserConfiguration, or null
 * @param track The track being loaded
 * @param queue The resolved queue of tracks
 * @param startIndex The index of the track in the queue
 * @param intercepted Value to return when the handler is present and invoked
 * @param defaultBehavior Value to return when no handler is configured
 * @return Result of [intercepted] if handler was invoked, result of [defaultBehavior] otherwise
 */
suspend fun <T> handleTrackLoad(
  handler: ((event: TrackLoadEvent) -> Promise<Promise<Unit>>)?,
  track: Track,
  queue: Array<Track>,
  startIndex: Double,
  intercepted: () -> T,
  defaultBehavior: suspend () -> T,
): T {
  if (handler != null) {
    val event = TrackLoadEvent(track, queue, startIndex)
    try {
      val outerPromise = handler(event)
      val innerPromise = outerPromise.await()
      innerPromise.await()
    } catch (e: Exception) {
      Timber.e(e, "handleTrackLoad failed")
    }
    return intercepted()
  }
  return defaultBehavior()
}
