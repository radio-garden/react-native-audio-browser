package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.SleepTimer as NitroSleepTimer
import com.margelo.nitro.audiobrowser.SleepTimerEndOfTrack
import com.margelo.nitro.audiobrowser.SleepTimerTime
import com.margelo.nitro.core.NullType
import timber.log.Timber

/**
 * Owns the Sleep Timer's interplay with the volume fade and its JS event emission: the timer counts
 * down natively (the webview may be asleep), an optional fade finishes exactly at the deadline, and
 * completion pauses — never stops — with the pre-fade volume restored so a half-asleep listener can
 * resume at full volume. Mirrors iOS's SleepTimerManager. [pause] is the player's pause;
 * [onChanged] emits onSleepTimerChanged.
 */
internal class SleepTimerManager(
  private val volumeFader: VolumeFader,
  private val pause: () -> Unit,
  private val onChanged: (NitroSleepTimer) -> Unit,
) {

  private val timer =
    object : SleepTimer() {
      override fun onFadeStart(durationSeconds: Double) {
        volumeFader.start(durationSeconds)
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

  /** The current state: a time-based deadline, end-of-track, or none. */
  fun get(): NitroSleepTimer =
    when {
      timer.time != null -> NitroSleepTimer.create(SleepTimerTime(timer.time!!))
      timer.sleepWhenPlayedToEnd -> NitroSleepTimer.create(SleepTimerEndOfTrack(true))
      else -> none()
    }

  /**
   * Pauses playback after [seconds], optionally fading out over the last [fadeDuration] seconds
   * (clamped to [seconds]).
   */
  fun setAfter(seconds: Double, fadeDuration: Double? = null) {
    timer.sleepAfter(seconds, fadeDuration)
    onChanged(get())
  }

  /** Pauses playback when the current track finishes playing. */
  fun setEndOfTrack() {
    timer.sleepWhenPlayedToEnd()
    onChanged(get())
  }

  /** Clears the active timer. @return true when a timer was actually cleared. */
  fun clear(): Boolean {
    val wasRunning = timer.clear()
    if (wasRunning) onChanged(none())
    return wasRunning
  }

  /** Pauses when an end-of-track sleep is armed; called when a track finishes naturally. */
  fun onTrackEnd() {
    if (timer.sleepWhenPlayedToEnd) {
      Timber.d("Sleep timer triggered on track end, pausing playback")
      timer.clear()
      pause()
      onChanged(none())
    }
  }
}
