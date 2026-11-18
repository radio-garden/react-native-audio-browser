package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.PlaybackState
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs [onTick] on the main thread every interval while the latest playback state passed to
 * [onPlaybackStateChanged] satisfies [isActive]. Starts inactive until the first state arrives.
 */
class PlaybackTimer(
  private val isActive: (PlaybackState) -> Boolean,
  private val onTick: () -> Unit,
) {
  private val scope = MainScope()
  private var job: Job? = null
  private var interval: Double? = null // seconds
  private var active = false

  /**
   * Sets the interval in seconds; null or <= 0 stops the timer. A new value restarts it, so the
   * next tick comes a full interval later.
   */
  fun setInterval(value: Double?) {
    val normalized = if (value != null && value > 0) value else null
    if (normalized == interval) return
    interval = normalized
    stop() // start() is a no-op while a job runs
    reconcile()
  }

  fun onPlaybackStateChanged(state: PlaybackState) {
    active = isActive(state)
    reconcile()
  }

  private fun reconcile() {
    val interval = interval
    if (interval != null && active) start(interval) else stop()
  }

  private fun start(interval: Double) {
    if (job != null) return
    job =
      scope.launch {
        while (true) {
          delay((interval * 1000).toLong())
          onTick()
        }
      }
  }

  private fun stop() {
    job?.cancel()
    job = null
  }
}
