package com.audiobrowser.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Ramps the player volume to silence over a duration (the sleep timer's fade), on the main looper.
 * The gain follows a squared curve: a linear gain ramp sounds like a late cutoff, not a fade.
 */
class VolumeFader(private val getVolume: () -> Float, private val setVolume: (Float) -> Unit) {
  private val handler = Handler(Looper.getMainLooper())
  private var stepRunnable: Runnable? = null

  /** The pre-fade volume; non-null from [start] until [cancel] or [resolve], even at silence. */
  var originalVolume: Float? = null
    private set

  val isActive: Boolean
    get() = originalVolume != null

  fun start(durationSeconds: Double) {
    // Restarting mid-fade keeps the first fade's baseline, not the partly faded current volume.
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
   * Stops the ramp. With [restoreVolume] the pre-fade volume comes back at once; without it the
   * volume stays where the ramp left it and the pre-fade volume is forgotten.
   */
  fun cancel(restoreVolume: Boolean) {
    stepRunnable?.let { handler.removeCallbacks(it) }
    stepRunnable = null
    val original = originalVolume ?: return
    originalVolume = null
    if (restoreVolume) setVolume(original)
  }

  /**
   * Stops the ramp, runs [halt], then restores the pre-fade volume, in that order so no audio plays
   * at full volume before the halt. Restores nothing when no fade was running.
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
