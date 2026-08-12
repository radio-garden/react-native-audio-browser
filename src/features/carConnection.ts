import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

// MARK: - Getters

/**
 * Whether a car is currently connected: a CarPlay scene on iOS, an
 * Android Auto / Android Automotive connection on Android (via the
 * androidx.car.app CarConnection provider). Always false on web.
 */
export function isCarConnected(): boolean {
  return nativeBrowser.isCarConnected()
}

// MARK: - Event Callbacks

/**
 * Subscribes to car connection changes (CarPlay on iOS, Android Auto /
 * Android Automotive on Android). Never fires on web.
 * @param callback - Called with the new connection state
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onCarConnectedChanged = NativeUpdatedValue.emitterize<boolean>(
  (cb) => (nativeBrowser.onCarConnectedChanged = cb)
)

// MARK: - Hooks

/**
 * Hook that returns whether a car is connected and updates on changes
 * (always false on web).
 */
export function useCarConnected(): boolean {
  return useNativeUpdatedValue(isCarConnected, onCarConnectedChanged)
}
