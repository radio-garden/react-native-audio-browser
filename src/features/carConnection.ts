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
 * Emits when a car connects or disconnects (CarPlay on iOS, Android Auto /
 * Android Automotive on Android), with the new state. Subscribe with
 * `addListener`, which returns the unsubscribe function. Never emits on web.
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
