package com.audiobrowser.player

/**
 * Caps how often the player recovers from a media3
 * [androidx.media3.common.util.StuckPlayerException] before it surfaces a terminal error. Once the
 * cap is passed, every call gives up until [reset], so a dead stream yields one error instead of a
 * recover/error sawtooth.
 *
 * It holds no time window on purpose: a stream that re-stalls just outside one would recover
 * forever. Whether the stream is alive is the caller's call, from playback progress.
 */
class StuckRecoveryPolicy(private val maxRecoveries: Int = DEFAULT_MAX_RECOVERIES) {
  enum class Decision {
    RECOVER,
    GIVE_UP,
  }

  private var recoveryCount = 0

  /**
   * Records a stuck event. [Decision.RECOVER] for the first [maxRecoveries] calls, then
   * [Decision.GIVE_UP] until [reset].
   */
  fun onStuck(): Decision {
    recoveryCount++
    return if (recoveryCount > maxRecoveries) Decision.GIVE_UP else Decision.RECOVER
  }

  /** Refills the budget. Call on sustained playback or a track change. */
  fun reset() {
    recoveryCount = 0
  }

  companion object {
    private const val DEFAULT_MAX_RECOVERIES = 3
  }
}
