import { nativePlayer } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

// MARK: - Getters

/**
 * Gets the current network connectivity state.
 * @returns true if the device is online, false otherwise
 */
export function getOnline(): boolean {
  return nativePlayer.getOnline()
}

// MARK: - Event Callbacks

/**
 * Subscribes to network connectivity changes.
 * @param callback - Called when network state changes
 * @returns Cleanup function to unsubscribe
 */
export const onOnlineChanged = NativeUpdatedValue.emitterize<boolean>(
  (cb) => (nativePlayer.onOnlineChanged = cb)
)

// MARK: - Hooks

/**
 * Hook that returns the current network connectivity state and updates when it changes.
 * @returns true if the device is online, false otherwise
 */
export function useOnline(): boolean {
  return useNativeUpdatedValue(getOnline, onOnlineChanged)
}
