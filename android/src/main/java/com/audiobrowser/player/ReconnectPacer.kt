package com.audiobrowser.player

import kotlin.math.min
import kotlin.math.pow

sealed interface ReconnectWait {
  data class After(val ms: Long) : ReconnectWait

  /** Offline: no connect until the network returns; call `networkReturned` and ask again then. */
  data object UntilOnline : ReconnectWait

  /** More than `maxRetries` connections in a row delivered nothing. */
  data object GiveUp : ReconnectWait
}

/**
 * When the next connect goes out after one ends (docs/adr/0014, rules 5 to 8), for one sequence of
 * connections made one at a time. Pure and not thread-safe: time comes in as milliseconds on one
 * monotonic clock.
 *
 * The 1 s floor counts from when the last connection started and the backoff from when it ended, so
 * the floor binds only after a connection that delivered audio.
 *
 * @param maxRetries connections in a row that may deliver nothing before giving up; null for no
 *   limit
 */
class ReconnectPacer(var maxRetries: Int? = null) {
  companion object {
    const val MIN_CONNECT_INTERVAL_MS = 1_000L
    const val FIRST_BACKOFF_MS = 1_000L
    const val MAX_BACKOFF_MS = 5_000L
    const val BACKOFF_GROWTH = 1.5
  }

  private var deliveredNothingInARow = 0
  private var lastStartedAtMs: Long? = null
  private var nextConnectMs: Long? = null

  /**
   * Starts the count and the waits over: on a new Load (a track change, or a restart from the
   * terminal error) and when the play intent comes up again. Never on a retry's reload.
   */
  fun reset() {
    deliveredNothingInARow = 0
    lastStartedAtMs = null
    nextConnectMs = null
  }

  /** Drops what remains of the backoff, keeping the floor: the next connect goes out at once. */
  fun networkReturned() {
    nextConnectMs = lastStartedAtMs?.let { it + MIN_CONNECT_INTERVAL_MS }
  }

  /**
   * Records how a connection ended. One that ends while offline does not grow the backoff: it
   * delivered nothing for want of a network, not of a source. A stall the watch gives up on reports
   * what its connection delivered.
   */
  fun connectionEnded(deliveredAudio: Boolean, startedAtMs: Long, atMs: Long, online: Boolean) {
    lastStartedAtMs = startedAtMs
    var next = startedAtMs + MIN_CONNECT_INTERVAL_MS
    if (deliveredAudio) {
      deliveredNothingInARow = 0
    } else if (online) {
      deliveredNothingInARow++
      next = maxOf(next, atMs + backoffMs())
    }
    nextConnectMs = next
  }

  fun wait(atMs: Long, online: Boolean): ReconnectWait {
    maxRetries?.let { if (deliveredNothingInARow > it) return ReconnectWait.GiveUp }
    if (!online) return ReconnectWait.UntilOnline
    return ReconnectWait.After(maxOf(0, (nextConnectMs ?: atMs) - atMs))
  }

  private fun backoffMs(): Long {
    val grown = FIRST_BACKOFF_MS * BACKOFF_GROWTH.pow(deliveredNothingInARow - 1)
    return min(grown, MAX_BACKOFF_MS.toDouble()).toLong()
  }
}
