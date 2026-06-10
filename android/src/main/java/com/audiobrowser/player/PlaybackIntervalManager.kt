package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.PlaybackState
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Emits a fixed-cadence tick while playback is active AND enabled. */
class PlaybackIntervalManager(private val onTick: () -> Unit) {
  private val scope = MainScope()
  private var job: Job? = null
  private var enabled = false
  private var active = false

  fun setEnabled(value: Boolean) {
    if (enabled == value) return
    enabled = value
    reconcile()
  }

  fun onPlaybackStateChanged(state: PlaybackState) {
    active =
      when (state) {
        // Only actual playback counts as "playback time"; buffering/loading
        // freeze the clock so consumers measure cumulative *playing* time.
        PlaybackState.PLAYING -> true
        PlaybackState.LOADING,
        PlaybackState.BUFFERING,
        PlaybackState.PAUSED,
        PlaybackState.STOPPED,
        PlaybackState.ENDED,
        PlaybackState.ERROR -> false
        else -> active
      }
    reconcile()
  }

  private fun reconcile() {
    if (enabled && active) start() else stop()
  }

  private fun start() {
    if (job != null) return
    job =
      scope.launch {
        while (true) {
          delay(TICK_MS)
          onTick()
        }
      }
  }

  private fun stop() {
    job?.cancel()
    job = null
  }

  companion object {
    private const val TICK_MS = 1000L
  }
}
