// MARK: - Getters

import { nativeBrowser } from "../../native"

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
 * @param rate - The playback rate to change to, where 0.5 would be half speed,
 * 1 would be regular speed, 2 would be double speed etc.
 */
export function setRate(rate: number): void {
  nativeBrowser.setRate(rate)
}
