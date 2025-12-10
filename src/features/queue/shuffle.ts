import { nativeBrowser } from '../../native'
import { NativeUpdatedValue } from '../../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../../utils/useNativeUpdatedValue'

// MARK: - Getters

/**
 * Gets whether shuffle mode is enabled.
 */
export function getShuffle(): boolean {
  return nativeBrowser.getShuffleEnabled()
}

/**
 * Sets whether shuffle mode is enabled.
 * When enabled, tracks play in a randomized order.
 * @param enabled - Whether to enable shuffle mode
 */
export function setShuffle(enabled: boolean): void {
  nativeBrowser.setShuffleEnabled(enabled)
}

/**
 * Toggles shuffle mode on/off.
 */
export function toggleShuffle(): void {
  setShuffle(!getShuffle())
}

// MARK: - Event Callbacks

/**
 * Subscribes to shuffle mode changes.
 * @param callback - Called when shuffle mode changes
 * @returns Cleanup function to unsubscribe
 */
export const onShuffleChanged = NativeUpdatedValue.emitterize<boolean>(
  (cb) => (nativeBrowser.onPlaybackShuffleModeChanged = cb)
)

// MARK: - Hooks

/**
 * Hook that returns whether shuffle mode is enabled and updates when it changes.
 * @returns Whether shuffle mode is enabled
 */
export function useShuffle(): boolean {
  return useNativeUpdatedValue(getShuffle, onShuffleChanged)
}
