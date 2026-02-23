import type { IosOutput, IosOutputType } from '../specs/audio-browser.nitro'
import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

export type { IosOutput, IosOutputType }

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
 * Gets the current audio output info.
 * Always returns a value on iOS, undefined on Android.
 */
export function getIosOutput(): IosOutput | undefined {
  return nativeBrowser.getIosOutput()
}

// MARK: - Event Callbacks

/**
 * Subscribes to output state changes (iOS only).
 * Never fires on Android.
 * @param callback - Called with output info when output changes
 * @returns Cleanup function to unsubscribe
 */
export const onIosOutputChanged = NativeUpdatedValue.emitterize<IosOutput>(
  (cb) => (nativeBrowser.onIosOutputChanged = cb)
)

// MARK: - Hooks

/**
 * Hook that returns the current audio output info.
 * Updates when the output changes (e.g., AirPods connected/disconnected).
 * Always returns a value on iOS, undefined on Android.
 */
export function useIosOutput(): IosOutput | undefined {
  return useNativeUpdatedValue(getIosOutput, onIosOutputChanged)
}
