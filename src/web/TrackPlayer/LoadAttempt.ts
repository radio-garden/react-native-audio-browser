/**
 * One attempt at loading a track, from the call that asked for it through to
 * the continuations that finish it.
 *
 * A load on web spans two async boundaries — resolving the URL, then Shaka's
 * own `load()` — and each has a continuation that must do nothing if something
 * else has taken over in the meantime. Handing every continuation the same
 * token turns that into one question, asked the same way in each place.
 *
 * Supersession is registered where it happens: {@link Player.beginLoadAttempt}
 * cancels the previous attempt the moment a new one starts, and `stop()`
 * cancels without starting one. Two integer generations used to stand in for
 * this, incremented at different depths — the outer one at the call, the inner
 * one only once the URL had resolved — so a load superseded during that gap
 * still ran its own success path.
 */
export class LoadAttempt {
  private cancelled = false

  /** Abandons this attempt; every continuation holding it will bail. */
  cancel(): void {
    this.cancelled = true
  }

  /** Whether this attempt still owns the player. */
  get isCurrent(): boolean {
    return !this.cancelled
  }
}
