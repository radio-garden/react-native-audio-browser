package com.audiobrowser.player

/**
 * When automatic retry gives up: after [recoveryMs] of silence, or, for a Load that never played,
 * [firstConnectMs] after its first failure within one online stretch (ADR 0014, rules 1 to 4). Pure
 * and not thread-safe: times are on one monotonic clock, and nothing fires on its own. Every input
 * can move the deadline, so read [deadlineMs] again after each.
 */
class SilenceBudget(
  var recoveryMs: Long = 120_000,
  var firstConnectMs: Long = 12_000,
  private var online: Boolean,
) {
  companion object {
    /** The audio a silence needs, in a row, to end; a shorter burst leaves its deadline. */
    const val AUDIBLE_TO_END_SILENCE_MS = 5_000L
  }

  /** Whether the current Load has rendered audio; only [loadStarted] clears it. */
  var hasPlayed = false
    private set

  private var playIntent = false
  private var audibleSinceMs: Long? = null
  private var silentSinceMs: Long? = null
  private var firstOnlineFailureMs: Long? = null

  /**
   * When to give up, or null while audio plays or no audio is wanted. It can lie in the past, which
   * means give up at once.
   */
  val deadlineMs: Long?
    get() {
      val silentSince = silentSinceMs
      if (!playIntent || audibleSinceMs != null || silentSince == null) return null
      val recovery = silentSince + recoveryMs
      val firstFailure = firstOnlineFailureMs
      if (hasPlayed || !online || firstFailure == null) return recovery
      return minOf(recovery, firstFailure + firstConnectMs)
    }

  /**
   * A new Load: a track change, or a restart from the terminal error. Never a retry's reload, which
   * would restart the silence on every reconnect.
   */
  fun loadStarted(atMs: Long) {
    hasPlayed = false
    audibleSinceMs = null
    firstOnlineFailureMs = null
    silentSinceMs = if (playIntent) atMs else null
  }

  fun playIntentChanged(up: Boolean, atMs: Long) {
    if (up == playIntent) return
    playIntent = up
    firstOnlineFailureMs = null
    silentSinceMs = if (up && audibleSinceMs == null) atMs else null
  }

  fun audioStarted(atMs: Long) {
    if (audibleSinceMs != null) return
    audibleSinceMs = atMs
    hasPlayed = true
  }

  fun audioStopped(atMs: Long) {
    val audibleSince = audibleSinceMs ?: return
    audibleSinceMs = null
    if (!playIntent) return
    if (silentSinceMs == null || atMs - audibleSince >= AUDIBLE_TO_END_SILENCE_MS) {
      silentSinceMs = atMs
    }
  }

  fun onlineChanged(online: Boolean) {
    this.online = online
    if (!online) firstOnlineFailureMs = null
  }

  /** Every connection's end, whatever it delivered. */
  fun connectionEnded(atMs: Long) {
    if (online && firstOnlineFailureMs == null) firstOnlineFailureMs = atMs
  }
}
