package com.audiobrowser.player

import android.os.Handler
import android.os.Looper

/**
 * Manages a sleep timer that calls a completion callback after a specified duration.
 *
 * The timer stores the expiration time as a timestamp and uses a Handler to schedule the completion
 * callback. Supports both time-based and end-of-track modes.
 */
open class SleepTimer {
  var time: Double? = null
    private set

  var sleepWhenPlayedToEnd: Boolean = false
    private set

  private var runnable: Runnable? = null
  private val handler = Handler(Looper.getMainLooper())

  val isRunning: Boolean
    get() = time != null || sleepWhenPlayedToEnd

  /**
   * Sets a timer to complete after the specified number of seconds.
   *
   * @param seconds Number of seconds until the timer completes
   */
  fun sleepAfter(seconds: Double) {
    stopTimer()
    sleepWhenPlayedToEnd = false

    val runnable = Runnable { complete() }
    this.runnable = runnable
    handler.postDelayed(runnable, (seconds * 1000).toLong())
    time = System.currentTimeMillis() + (seconds * 1000)
  }

  /**
   * Sets the timer to complete when the current track finishes playing. Note: The actual completion
   * logic must be handled by the caller when the track ends.
   */
  fun sleepWhenPlayedToEnd() {
    stopTimer()
    time = null
    sleepWhenPlayedToEnd = true
  }

  /**
   * Clears the active timer.
   *
   * @return true if a timer was running and was cleared, false otherwise
   */
  fun clear(): Boolean {
    val wasRunning = isRunning
    stopTimer()
    time = null
    sleepWhenPlayedToEnd = false
    return wasRunning
  }

  /** Override this method to handle timer completion. */
  open fun onComplete() {
    // Default implementation does nothing - override in subclass
  }

  private fun complete() {
    if (!isRunning) return
    clear()
    onComplete()
  }

  private fun stopTimer() {
    runnable?.let {
      handler.removeCallbacks(it)
      runnable = null
    }
  }
}
