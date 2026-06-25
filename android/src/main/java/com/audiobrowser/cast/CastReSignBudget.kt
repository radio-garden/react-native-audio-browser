package com.audiobrowser.cast

/**
 * Pure per-item bookkeeping for the bounded reactive re-sign of stale Cast queue URLs (see ADR
 * 0003): caps attempts per item id and dedups re-entrant triggers while a re-sign is in flight, so
 * a genuinely dead stream surfaces a real error instead of looping forever (mirrors
 * `StuckRecoveryPolicy`'s philosophy).
 *
 * Lives in **main** (no Cast SDK) so it compiles in the default build and is unit-testable. The
 * `cast` sourceset's `CastReSign` keeps the SDK resolve / `queueUpdateItems` calls and uses this for
 * the bookkeeping. Not thread-safe — callers confine to the main (RemoteMediaClient callback)
 * thread.
 */
class CastReSignBudget(private val maxAttemptsPerItem: Int = 3) {
  private val attemptsByItemKey = mutableMapOf<String, Int>()
  private val inFlight = mutableSetOf<String>()

  /**
   * Decides whether to dispatch a re-sign for [key] and, when yes, claims an attempt + marks it
   * in-flight (so re-entrant idle+error callbacks for the same item don't each burn the budget).
   *
   * - [Decision.ATTEMPT] — dispatch; remember to call [markDone] when the dispatch settles.
   * - [Decision.IN_FLIGHT] — a re-sign for this item is already running; do nothing (caller should
   *   NOT surface an error yet).
   * - [Decision.EXHAUSTED] — the per-item cap is spent; caller surfaces the real error.
   */
  fun shouldAttempt(key: String): Decision {
    if (key in inFlight) return Decision.IN_FLIGHT
    val attempts = attemptsByItemKey.getOrDefault(key, 0)
    if (attempts >= maxAttemptsPerItem) return Decision.EXHAUSTED
    attemptsByItemKey[key] = attempts + 1
    inFlight.add(key)
    return Decision.ATTEMPT
  }

  /** Clears the in-flight mark for [key] once its dispatched re-sign settles (success or failure). */
  fun markDone(key: String) {
    inFlight.remove(key)
  }

  /** Resets all counters — call on a fresh queue load / new Cast session. */
  fun reset() {
    attemptsByItemKey.clear()
    inFlight.clear()
  }

  enum class Decision {
    ATTEMPT,
    IN_FLIGHT,
    EXHAUSTED,
  }
}
