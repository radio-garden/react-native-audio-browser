/**
 * Ramps the player volume down over a duration (sleep-timer fade). The gain
 * follows a squared curve: loudness perception is logarithmic, so a linear
 * gain ramp sounds like a late cutoff rather than a fade.
 */
export class VolumeFader {
  private interval: NodeJS.Timeout | null = null

  /**
   * Volume captured when the fade started; restored after the fade resolves.
   * Non-null from fade start until cancel/completion, even once the ramp has
   * reached silence.
   */
  private preFadeVolume: number | null = null

  constructor(
    private getVolume: () => number,
    private setVolume: (volume: number) => void
  ) {}

  get isActive(): boolean {
    return this.preFadeVolume !== null
  }

  start(durationSeconds: number): void {
    // If a fade is already running, keep its captured volume as the baseline —
    // the current (partially faded) volume is not the value to restore.
    const baseVolume = this.preFadeVolume ?? this.getVolume()
    this.cancel(false)
    this.preFadeVolume = baseVolume
    if (durationSeconds <= 0) return

    const startTime = Date.now()
    this.interval = setInterval(() => {
      const progress = Math.min(
        1,
        (Date.now() - startTime) / (durationSeconds * 1000)
      )
      this.setVolume(baseVolume * (1 - progress) ** 2)
      if (progress >= 1 && this.interval !== null) {
        clearInterval(this.interval)
        this.interval = null
      }
    }, 50)
  }

  /**
   * Stops the ramp. With `restoreVolume` the pre-fade volume is put back
   * immediately (cancellation); without it the caller restores after pausing
   * (completion path — restoring first would let full-volume audio slip out).
   */
  cancel(restoreVolume: boolean): void {
    if (this.interval !== null) {
      clearInterval(this.interval)
      this.interval = null
    }
    const preFadeVolume = this.preFadeVolume
    this.preFadeVolume = null
    if (restoreVolume && preFadeVolume !== null) this.setVolume(preFadeVolume)
  }

  /**
   * Resolves the fade around a halting action: stops the ramp, runs `halt`,
   * then restores the pre-fade volume — in that order, because restoring
   * before halting would let full-volume audio slip out. The restore is
   * skipped when no fade was running.
   */
  resolve(halt: () => void): void {
    const preFadeVolume = this.preFadeVolume
    this.cancel(false)
    halt()
    if (preFadeVolume !== null) this.setVolume(preFadeVolume)
  }
}
