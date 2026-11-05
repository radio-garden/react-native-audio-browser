import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

// MARK: - Getters

/**
 * Gets the current network connectivity state.
 * On Android it reads `true` before `setupPlayer` has connected the player, and
 * while its service restarts.
 * @returns true if the device is online, false otherwise
 */
export function getOnline(): boolean {
  return nativeBrowser.getOnline()
}

// MARK: - Event Callbacks

/**
 * Subscribes to network connectivity changes.
 * On Android it fires nothing until `setupPlayer` has connected the player, and then
 * fires once with the current state.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onOnlineChanged = NativeUpdatedValue.emitterize<boolean>(
  (cb) => (nativeBrowser.onOnlineChanged = cb)
)

// MARK: - Hooks

/**
 * Hook that returns the current network connectivity state and updates when it changes.
 * On Android it reads `true` until `setupPlayer` has connected the player.
 * @returns true if the device is online, false otherwise
 */
export function useOnline(): boolean {
  return useNativeUpdatedValue(getOnline, onOnlineChanged)
}
