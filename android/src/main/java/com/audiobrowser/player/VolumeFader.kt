package com.audiobrowser.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Ramps the player volume down over a duration (sleep-timer fade). The gain follows a squared
 * curve: loudness perception is logarithmic, so a linear gain ramp sounds like a late cutoff rather
 * than a fade.
 */
class VolumeFader(
  private val getVolume: () -> Float,
  private val setVolume: (Float) -> Unit,
) {
  private val handler = Handler(Looper.getMainLooper())
  private var stepRunnable: Runnable? = null

  /**
   * Volume captured when the fade started; restored after the fade resolves. Non-null from fade
   * start until cancel/completion, even once the ramp has reached silence.
   */
  var originalVolume: Float? = null
    private set

  val isActive: Boolean
    get() = originalVolume != null

  fun start(durationSeconds: Double) {
    // If a fade is already running, keep its captured volume as the baseline — the current
    // (partially faded) volume is not the value to restore.
    val baseVolume = originalVolume ?: getVolume()
    cancel(restoreVolume = false)
    originalVolume = baseVolume
    if (durationSeconds <= 0) return

    val startTime = SystemClock.uptimeMillis()
    val durationMs = durationSeconds * 1000
    val runnable =
      object : Runnable {
        override fun run() {
          val progress = ((SystemClock.uptimeMillis() - startTime) / durationMs).coerceAtMost(1.0)
          val remaining = (1 - progress).toFloat()
          setVolume(baseVolume * remaining * remaining)
          if (progress < 1.0) {
            handler.postDelayed(this, STEP_INTERVAL_MS)
          } else {
            stepRunnable = null
          }
        }
      }
    stepRunnable = runnable
    handler.post(runnable)
  }

  /**
   * Stops the ramp. With [restoreVolume] the pre-fade volume is put back immediately
   * (cancellation); without it the caller restores after pausing (completion path — restoring
   * first would let full-volume audio slip out).
   */
  fun cancel(restoreVolume: Boolean) {
    stepRunnable?.let { handler.removeCallbacks(it) }
    stepRunnable = null
    val original = originalVolume ?: return
    originalVolume = null
    if (restoreVolume) setVolume(original)
  }

  /**
   * Resolves the fade around a halting action: stops the ramp, runs [halt], then restores the
   * pre-fade volume — in that order, because restoring before halting would let full-volume audio
   * slip out. The restore is skipped when no fade was running.
   */
  fun resolve(halt: () -> Unit) {
    val preFadeVolume = originalVolume
    cancel(restoreVolume = false)
    halt()
    preFadeVolume?.let { setVolume(it) }
  }

  companion object {
    private const val STEP_INTERVAL_MS = 50L
  }
}
