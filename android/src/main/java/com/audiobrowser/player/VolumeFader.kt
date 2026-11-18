package com.audiobrowser.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.pow

/**
 * Ramps the player volume to silence by a deadline, on the main looper. The gain falls linearly in
 * decibels to [FLOOR_DECIBELS], so every second of the fade is heard; a gain that falls linearly
 * spends its last seconds inaudible.
 */
class VolumeFader(private val getVolume: () -> Float, private val setVolume: (Float) -> Unit) {
  private val handler = Handler(Looper.getMainLooper())
  private var stepRunnable: Runnable? = null

  /** The pre-fade volume; non-null from [start] until [cancel] or [resolve], even at silence. */
  var originalVolume: Float? = null
    private set

  val isActive: Boolean
    get() = originalVolume != null

  /**
   * Starts a fade that reaches silence at [endUptimeMs] ([SystemClock.uptimeMillis]). Started late,
   * it joins the ramp where it would be by now; restarted, it keeps the first fade's baseline.
   */
  fun start(endUptimeMs: Long, durationSeconds: Double) {
    val baseVolume = originalVolume ?: getVolume()
    cancel(restoreVolume = false)
    originalVolume = baseVolume
    if (durationSeconds <= 0) return

    val durationMs = durationSeconds * 1000
    val runnable =
      object : Runnable {
        override fun run() {
          val progress = 1 - (endUptimeMs - SystemClock.uptimeMillis()) / durationMs
          setVolume(baseVolume * gain(progress))
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
   * Stops the ramp. Without [restoreVolume] the volume stays where the ramp left it and the
   * pre-fade volume is forgotten.
   */
  fun cancel(restoreVolume: Boolean) {
    stepRunnable?.let { handler.removeCallbacks(it) }
    stepRunnable = null
    val original = originalVolume ?: return
    originalVolume = null
    if (restoreVolume) setVolume(original)
  }

  /**
   * Stops the ramp, runs [halt], then restores the pre-fade volume, so no audio plays at full
   * volume before the halt.
   */
  fun resolve(halt: () -> Unit) {
    val preFadeVolume = originalVolume
    cancel(restoreVolume = false)
    halt()
    preFadeVolume?.let { setVolume(it) }
  }

  companion object {
    private const val STEP_INTERVAL_MS = 50L
    const val FLOOR_DECIBELS = -50.0

    /** The gain, relative to the pre-fade volume, [progress] (0..1) of the way through a fade. */
    fun gain(progress: Double): Float =
      if (progress >= 1) 0f
      else 10.0.pow(FLOOR_DECIBELS * progress.coerceAtLeast(0.0) / 20).toFloat()
  }
}
