import { nativePlayer } from '../native'
// MARK: - Getters

/**
 * Gets the volume of the player as a number between 0 and 1.
 */
export function getVolume(): number {
  return nativePlayer.getVolume()
}

// MARK: - Setters

/**
 * Sets the volume of the player.
 * @param level - The volume as a number between 0 and 1.
 */
export function setVolume(level: number): void {
  nativePlayer.setVolume(level)
}
