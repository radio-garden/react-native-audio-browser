/**
 * A restartable interval timer that fires only while a gate predicate holds —
 * the web analog of Android's playback-state-gated `PlaybackTimer`. The timer
 * keeps running while the gate is closed; ticks are simply skipped, so it
 * resumes the moment the gate reopens (e.g. buffering → playing) without being
 * restarted.
 */
export class PlaybackTimer {
  private handle: ReturnType<typeof setInterval> | undefined

  /**
   * (Re)starts the timer. Every `intervalMs` it calls `onTick`, but only when
   * `shouldTick()` returns true. A non-positive interval leaves it stopped.
   */
  start(
    intervalMs: number,
    shouldTick: () => boolean,
    onTick: () => void
  ): void {
    this.stop()
    if (intervalMs <= 0) return
    this.handle = setInterval(() => {
      if (shouldTick()) onTick()
    }, intervalMs)
  }

  stop(): void {
    if (this.handle !== undefined) {
      clearInterval(this.handle)
      this.handle = undefined
    }
  }
}
