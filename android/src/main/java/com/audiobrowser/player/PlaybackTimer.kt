package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.PlaybackState
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A playback-gated repeating timer. Runs [onTick] every [interval] seconds while the current
 * playback state satisfies [isActive]. A null interval (or <= 0) means stopped — the single
 * off-switch.
 */
class PlaybackTimer(
  private val isActive: (PlaybackState) -> Boolean,
  private val onTick: () -> Unit,
) {
  private val scope = MainScope()
  private var job: Job? = null
  private var interval: Double? = null
  private var active = false

  fun setInterval(value: Double?) {
    val normalized = if (value != null && value > 0) value else null
    if (normalized == interval) return
    interval = normalized
    stop() // restart with the new interval on reconcile
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
