// MARK: - Getters

import { nativeBrowser } from '../../native'

/**
 * Gets the playback rate where 0.5 would be half speed, 1 would be
 * regular speed and 2 would be double speed etc.
 */
export function getRate(): number {
  return nativeBrowser.getRate()
}

// MARK: - Setters

/**
 * Sets the playback rate.
 *
 * The rate is a speed multiplier, not a transport control — it is independent
 * of play/pause, and setting it while paused never starts playback. Use
 * {@link play} / {@link pause} for that.
 *
 * @param rate - The playback rate to change to, where 0.5 would be half speed,
 * 1 would be regular speed, 2 would be double speed etc. Must be greater than
 * zero; platforms disagree on what a non-positive rate does (iOS ignores it,
 * Android throws, web stalls the element), so treat it as unsupported.
 */
export function setRate(rate: number): void {
  nativeBrowser.setRate(rate)
}
