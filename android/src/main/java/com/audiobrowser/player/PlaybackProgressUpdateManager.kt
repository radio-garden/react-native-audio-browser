package com.audiobrowser.player

import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.margelo.nitro.audiobrowser.PlaybackState

class PlaybackProgressUpdateManager(private val onProgressUpdate: () -> Unit) {
  private val scope = MainScope()
  private var job: Job? = null
  private var updateInterval: Double? = null

  fun setUpdateInterval(interval: Double?) {
    if (interval == updateInterval) return
    updateInterval = if (interval != null && interval > 0) interval else null
    stop()
    if (updateInterval != null) {
      start()
    }
  }

  fun start() {
    if (job != null) return
    updateInterval?.let { interval ->
      job =
        scope.launch {
          while (true) {
            onProgressUpdate()
            delay((interval * 1000).toLong())
          }
        }
    }
  }

  fun stop() {
    job?.cancel()
    job = null
  }

  fun onPlaybackStateChanged(state: PlaybackState) {
    when (state) {
      // Start when playback is set to resume (loading, buffering) or playing
      PlaybackState.LOADING,
      PlaybackState.BUFFERING,
      PlaybackState.PLAYING -> start()

      // Stop when playback pauses, stops, or errors
      PlaybackState.PAUSED,
      PlaybackState.STOPPED,
      PlaybackState.ENDED,
      PlaybackState.ERROR -> stop()

      // No action for READY, IDLE, NONE
      else -> {}
    }
  }
}
