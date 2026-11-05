package com.audiobrowser.player

import android.os.Handler
import android.os.Looper

/**
 * A sleep timer whose hooks run on the main looper: [onComplete] at the deadline, and with a fade,
 * [onFadeStart] early enough that the fade ends at the deadline. Subclass it to act on the hooks.
 */
open class SleepTimer {
  // Deadline in epoch milliseconds; null when idle or in end-of-track mode.
  var time: Double? = null
    private set

  var sleepWhenPlayedToEnd: Boolean = false
    private set

  private var runnable: Runnable? = null
  private var fadeRunnable: Runnable? = null
  private val handler = Handler(Looper.getMainLooper())

  val isRunning: Boolean
    get() = time != null || sleepWhenPlayedToEnd

  /**
   * Completes after [seconds], replacing any armed timer. [fadeDuration] seconds is clamped to
   * [seconds]; null or non-positive means no fade.
   */
  fun sleepAfter(seconds: Double, fadeDuration: Double? = null) {
    stopTimer()
    onFadeCancel()
    sleepWhenPlayedToEnd = false

    val runnable = Runnable { complete() }
    this.runnable = runnable
    handler.postDelayed(runnable, (seconds * 1000).toLong())
    time = System.currentTimeMillis() + (seconds * 1000)

    if (fadeDuration != null && fadeDuration > 0) {
      val fade = minOf(fadeDuration, seconds)
      val fadeRunnable = Runnable {
        this.fadeRunnable = null
        onFadeStart(fade)
      }
      this.fadeRunnable = fadeRunnable
      handler.postDelayed(fadeRunnable, ((seconds - fade) * 1000).toLong())
    }
  }

  /**
   * Arms end-of-track mode, replacing any armed timer. The timer never completes this mode itself:
   * the caller acts when the track ends and calls [clear].
   */
  fun sleepWhenPlayedToEnd() {
    stopTimer()
    onFadeCancel()
    time = null
    sleepWhenPlayedToEnd = true
  }

  /** Disarms the timer; returns whether one was armed. */
  fun clear(): Boolean {
    val wasRunning = isRunning
    stopTimer()
    onFadeCancel()
    time = null
    sleepWhenPlayedToEnd = false
    return wasRunning
  }

  open fun onComplete() {}

  open fun onFadeStart(durationSeconds: Double) {}

  /** Called on every re-arm or clear, whether or not a fade is running; not on completion. */
  open fun onFadeCancel() {}

  private fun complete() {
    if (!isRunning) return
    // No onFadeCancel: onComplete pauses first, then restores the volume itself.
    stopTimer()
    time = null
    sleepWhenPlayedToEnd = false
    onComplete()
  }

  private fun stopTimer() {
    runnable?.let {
      handler.removeCallbacks(it)
      runnable = null
    }
    fadeRunnable?.let {
      handler.removeCallbacks(it)
      fadeRunnable = null
    }
  }
}
