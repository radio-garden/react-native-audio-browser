// MARK: - Getters

import { nativeBrowser } from '../../native'
import { NativeUpdatedValue } from '../../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../../utils/useNativeUpdatedValue'

/**
 * Gets the volume of the player as a number between 0 and 1.
 */
export function getVolume(): number {
  return nativeBrowser.getVolume()
}

/**
 * Gets the current system volume as a number between 0 and 1. On web, always 1.
 */
export function getSystemVolume(): number {
  return nativeBrowser.getSystemVolume()
}

// MARK: - Setters

/**
 * Sets the volume of the player.
 * @param level - The volume as a number between 0 and 1.
 */
export function setVolume(level: number): void {
  nativeBrowser.setVolume(level)
}

/**
 * Sets the system volume.
 * @param level - The volume as a number between 0 and 1.
 * @remarks A no-op on iOS, which has no public API for it, and on web.
 */
export function setSystemVolume(level: number): void {
  nativeBrowser.setSystemVolume(level)
}

// MARK: - Event Callbacks

/**
 * Emits the system volume (0 to 1) each time it changes. Never fires on web.
 * Subscribe with `onSystemVolumeChanged.addListener(cb)`, which returns an
 * unsubscribe function.
 */
export const onSystemVolumeChanged = NativeUpdatedValue.emitterize<number>(
  (cb) => (nativeBrowser.onSystemVolumeChanged = cb)
)

// MARK: - Hooks

/**
 * Hook that returns the current system volume and updates when it changes.
 * On web, always 1.
 * @returns The current system volume (0 to 1)
 */
export function useSystemVolume(): number {
  return useNativeUpdatedValue(getSystemVolume, onSystemVolumeChanged)
}
