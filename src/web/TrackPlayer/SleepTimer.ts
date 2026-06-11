/**
 * Manages a sleep timer that calls a completion callback after a specified duration.
 *
 * The timer stores the expiration time as a timestamp and uses setTimeout to schedule the completion
 * callback. Supports both time-based and end-of-track modes, plus an optional fade-out that starts
 * `fadeDuration` seconds before the deadline so silence lands exactly when the timer completes.
 */
export class SleepTimerManager {
  public time: number | null = null
  public sleepWhenPlayedToEnd: boolean = false
  private timeoutId: NodeJS.Timeout | null = null
  private fadeTimeoutId: NodeJS.Timeout | null = null

  get isRunning(): boolean {
    return this.time !== null || this.sleepWhenPlayedToEnd
  }

  /**
   * Sets a timer to complete after the specified number of seconds.
   *
   * @param seconds Number of seconds until the timer completes
   * @param fadeDuration Seconds over which to fade out before completion; clamped to `seconds`
   */
  sleepAfter(seconds: number, fadeDuration?: number): void {
    this.stopTimer()
    this.onFadeCancel()
    this.sleepWhenPlayedToEnd = false

    this.timeoutId = setTimeout(() => {
      this.complete()
    }, seconds * 1000)
    this.time = Date.now() + seconds * 1000

    if (fadeDuration !== undefined && fadeDuration > 0) {
      const fade = Math.min(fadeDuration, seconds)
      this.fadeTimeoutId = setTimeout(
        () => {
          this.fadeTimeoutId = null
          this.onFadeStart(fade)
        },
        (seconds - fade) * 1000
      )
    }
  }

  /**
   * Sets the timer to complete when the current track finishes playing. Note: The actual completion
   * logic must be handled by the caller when the track ends.
   */
  setToEndOfTrack(): void {
    this.stopTimer()
    this.onFadeCancel()
    this.time = null
    this.sleepWhenPlayedToEnd = true
  }

  /**
   * Clears the active timer.
   *
   * @return true if a timer was running and was cleared, false otherwise
   */
  clear(): boolean {
    const wasRunning = this.isRunning
    this.stopTimer()
    this.onFadeCancel()
    this.time = null
    this.sleepWhenPlayedToEnd = false
    return wasRunning
  }

  /** Override this method to handle timer completion. */
  protected onComplete(): void {
    // Default implementation does nothing - override in subclass
  }

  /** Override to begin fading the volume; the timer still completes at its deadline. */
  protected onFadeStart(_durationSeconds: number): void {
    // Default implementation does nothing - override in subclass
  }

  /** Override to handle a fade cancelled before completion — restore the volume. */
  protected onFadeCancel(): void {
    // Default implementation does nothing - override in subclass
  }

  private complete(): void {
    if (!this.isRunning) return
    // Tear down without onFadeCancel: completion pauses first, then the
    // completion handler restores the volume.
    this.stopTimer()
    this.time = null
    this.sleepWhenPlayedToEnd = false
    this.onComplete()
  }

  private stopTimer(): void {
    if (this.timeoutId !== null) {
      clearTimeout(this.timeoutId)
      this.timeoutId = null
    }
    if (this.fadeTimeoutId !== null) {
      clearTimeout(this.fadeTimeoutId)
      this.fadeTimeoutId = null
    }
  }
}
