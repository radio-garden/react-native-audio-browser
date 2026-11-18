package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.SleepTimer as NitroSleepTimer
import com.margelo.nitro.audiobrowser.SleepTimerEndOfTrack
import com.margelo.nitro.audiobrowser.SleepTimerTime
import com.margelo.nitro.core.NullType
import timber.log.Timber

/**
 * Runs the sleep timer's volume fade and emits each state change through [onChanged]. Completion
 * pauses rather than stops, and restores the pre-fade volume for the next play. Mirrors iOS's
 * SleepTimerManager.
 */
internal class SleepTimerManager(
  private val volumeFader: VolumeFader,
  private val pause: () -> Unit,
  private val onChanged: (NitroSleepTimer) -> Unit,
) {

  private val timer =
    object : SleepTimer() {
      override fun onFadeStart(deadlineUptimeMs: Long, durationSeconds: Double) {
        volumeFader.start(deadlineUptimeMs, durationSeconds)
      }

      override fun onFadeCancel() {
        volumeFader.cancel(restoreVolume = true)
      }

      override fun onComplete() {
        Timber.d("Sleep timer completed, pausing playback")
        volumeFader.resolve { pause() }
        onChanged(none())
      }
    }

  private fun none(): NitroSleepTimer = NitroSleepTimer.create(NullType.NULL)

  fun get(): NitroSleepTimer =
    when {
      timer.time != null -> NitroSleepTimer.create(SleepTimerTime(timer.time!!))
      timer.sleepWhenPlayedToEnd -> NitroSleepTimer.create(SleepTimerEndOfTrack(true))
      else -> none()
    }

  /**
   * Pauses after [seconds], fading out over the last [fadeDuration] seconds, clamped to [seconds].
   */
  fun setAfter(seconds: Double, fadeDuration: Double? = null) {
    timer.sleepAfter(seconds, fadeDuration)
    onChanged(get())
  }

  fun setEndOfTrack() {
    timer.sleepWhenPlayedToEnd()
    onChanged(get())
  }

  fun clear(): Boolean {
    val wasRunning = timer.clear()
    if (wasRunning) onChanged(none())
    return wasRunning
  }

  /** Pauses only if an end-of-track sleep is armed. */
  fun onTrackEnd() {
    if (timer.sleepWhenPlayedToEnd) {
      Timber.d("Sleep timer triggered on track end, pausing playback")
      timer.clear()
      pause()
      onChanged(none())
    }
  }
}
