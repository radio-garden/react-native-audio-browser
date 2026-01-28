import { nativeBrowser } from '../native'
import type { EqualizerSettings } from '../specs/audio-browser.nitro'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

export type { EqualizerSettings }

// MARK: - Getters

/**
 * Gets the current equalizer settings (Android only).
 * @returns Equalizer settings, or undefined if not available
 */
export function getEqualizerSettings(): EqualizerSettings | undefined {
  return nativeBrowser.getEqualizerSettings()
}

// MARK: - Setters

/**
 * Enables or disables the equalizer (Android only).
 * @param enabled - Whether to enable the equalizer
 */
export function setEqualizerEnabled(enabled: boolean): void {
  nativeBrowser.setEqualizerEnabled(enabled)
}

/**
 * Sets the equalizer to a preset (Android only).
 * @param preset - The name of the preset to apply
 */
export function setEqualizerPreset(preset: string): void {
  nativeBrowser.setEqualizerPreset(preset)
}

/**
 * Sets custom equalizer band levels (Android only).
 * @param levels - Array of level values for each band (in millibels)
 */
export function setEqualizerLevels(levels: number[]): void {
  nativeBrowser.setEqualizerLevels(levels)
}

// MARK: - Event Callbacks

/**
 * Subscribes to equalizer settings changes (Android only).
 * @param callback - Called when equalizer settings change
 * @returns Cleanup function to unsubscribe
 */
export const onEqualizerChanged =
  NativeUpdatedValue.emitterize<EqualizerSettings>(
    (cb) => (nativeBrowser.onEqualizerChanged = cb)
  )

// MARK: - Hooks

/**
 * Hook that returns the current equalizer settings and updates when they change (Android only).
 * @returns Current equalizer settings, or undefined if not available
 */
export function useEqualizerSettings(): EqualizerSettings | undefined {
  return useNativeUpdatedValue(getEqualizerSettings, onEqualizerChanged)
}
