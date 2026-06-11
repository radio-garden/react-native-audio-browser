import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

// MARK: - Getters

/**
 * Whether a CarPlay scene is currently connected (iOS only).
 * Always returns false on Android and web.
 */
export function isCarPlayConnected(): boolean {
  return nativeBrowser.isCarPlayConnected()
}

// MARK: - Event Callbacks

/**
 * Subscribes to CarPlay connection changes (iOS only).
 * Never fires on Android or web.
 * @param callback - Called with the new connection state
 * @returns Cleanup function to unsubscribe
 */
export const onCarPlayConnectedChanged = NativeUpdatedValue.emitterize<boolean>(
  (cb) => (nativeBrowser.onCarPlayConnectedChanged = cb)
)

// MARK: - Hooks

/**
 * Hook that returns whether CarPlay is connected and updates on changes
 * (iOS only — always false on Android and web).
 */
export function useCarPlayConnected(): boolean {
  return useNativeUpdatedValue(isCarPlayConnected, onCarPlayConnectedChanged)
}
