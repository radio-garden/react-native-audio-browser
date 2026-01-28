import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

// MARK: - Actions

/**
 * Opens the system output picker (iOS only).
 * Allows users to select output device (speaker, AirPlay, Bluetooth, etc.).
 * No-op on Android.
 */
export function openIosOutputPicker(): void {
  nativeBrowser.openIosOutputPicker()
}

// MARK: - Getters

/**
 * Returns whether the active output is external (iOS only).
 * Returns true for AirPlay, Bluetooth, headphones, CarPlay, etc.
 * Returns false when using the device's built-in speaker.
 * Always returns false on Android.
 */
export function isIosOutputExternal(): boolean {
  return nativeBrowser.isIosOutputExternal()
}

// MARK: - Event Callbacks

/**
 * Subscribes to output state changes (iOS only).
 * Fires when switching between built-in speaker and external outputs.
 * Never fires on Android.
 * @param callback - Called with true when active output is external, false otherwise
 * @returns Cleanup function to unsubscribe
 */
export const onIosOutputExternalChanged = NativeUpdatedValue.emitterize<boolean>(
  (cb) => (nativeBrowser.onIosOutputExternalChanged = cb)
)

// MARK: - Hooks

/**
 * Hook that returns whether the active output is external (iOS only).
 * Updates when the output changes (e.g., AirPods connected/disconnected).
 * Always returns false on Android.
 */
export function useIosOutputExternal(): boolean {
  return useNativeUpdatedValue(isIosOutputExternal, onIosOutputExternalChanged)
}
