package com.audiobrowser.player

/**
 * Caps how many times the player auto-recovers from a media3
 * [androidx.media3.common.util.StuckPlayerException] before surfacing a terminal error.
 *
 * media3 1.9.0 detects a stuck player and reports it through Player.Listener.onPlayerError. We
 * recover (re-prepare / rejoin live edge) rather than going to ERROR, because for a live-radio app
 * a transient stall should reconnect, not stop playback. But a genuinely dead stream would re-fire
 * forever, so we allow at most [maxRecoveries] recoveries. The budget refills only when the caller
 * has proof the stream is alive (sustained playback) or on a track change — both via [reset]. Once
 * the cap is exceeded the decision stays [Decision.GIVE_UP] (it does not self-refill), so a dead
 * stream produces one clean terminal error instead of a recover/error sawtooth.
 *
 * Deliberately holds no clock or time-window: a time-window keyed on recovery timing lets a stream
 * that re-stalls just outside the window recover indefinitely. "Is it alive?" is answered by the
 * caller from actual playback progress, not elapsed time.
 *
 * @param maxRecoveries Maximum recoveries allowed before giving up.
 */
class StuckRecoveryPolicy(private val maxRecoveries: Int = DEFAULT_MAX_RECOVERIES) {
  enum class Decision {
    RECOVER,
    GIVE_UP,
  }

  private var recoveryCount = 0

  /**
   * Records a stuck event and decides what to do. [Decision.RECOVER] means the caller should
   * re-prepare; [Decision.GIVE_UP] means recovery is exhausted and the caller should surface a
   * terminal error. Stays [Decision.GIVE_UP] until [reset] is called.
   */
  fun onStuck(): Decision {
    recoveryCount++
    return if (recoveryCount > maxRecoveries) Decision.GIVE_UP else Decision.RECOVER
  }

  /**
   * Refills the recovery budget. Call on proof the stream is alive (sustained playback) or on a
   * track change.
   */
  fun reset() {
    recoveryCount = 0
  }

  companion object {
    private const val DEFAULT_MAX_RECOVERIES = 3
  }
}
