import type { EqualizerSettings } from '../specs/audio-browser.nitro'
import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

export type { EqualizerSettings }

// MARK: - Getters

/**
 * Gets the current equalizer settings (Android only).
 * @returns Equalizer settings; undefined on iOS and web, and on Android before
 *   `setupPlayer` and until the player's audio session arrives
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
 * @param preset - A name from `settings.presets`; matched case-insensitively,
 *   an unknown name is ignored
 */
export function setEqualizerPreset(preset: string): void {
  nativeBrowser.setEqualizerPreset(preset)
}

/**
 * Sets custom equalizer band levels (Android only).
 * @param levels - One level per band, in millibels, within
 *   `lowerBandLevelLimit`…`upperBandLevelLimit`. The length must equal
 *   `bandCount`, or the call is ignored
 */
export function setEqualizerLevels(levels: number[]): void {
  nativeBrowser.setEqualizerLevels(levels)
}

// MARK: - Event Callbacks

/**
 * Subscribes to equalizer settings changes (Android only).
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onEqualizerChanged =
  NativeUpdatedValue.emitterize<EqualizerSettings>(
    (cb) => (nativeBrowser.onEqualizerChanged = cb)
  )

// MARK: - Hooks

/**
 * Hook that returns the current equalizer settings and updates when they change (Android only).
 * @returns Current equalizer settings; undefined on iOS and web, and on
 *   Android before `setupPlayer` and until the player's audio session arrives
 */
export function useEqualizerSettings(): EqualizerSettings | undefined {
  return useNativeUpdatedValue(getEqualizerSettings, onEqualizerChanged)
}
