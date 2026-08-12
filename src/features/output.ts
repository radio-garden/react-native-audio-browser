import type { Output, OutputType } from '../specs/audio-browser.nitro'
import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

export type { Output, OutputType }

// MARK: - Actions

/**
 * Presents the system audio output switcher so the listener can move playback to
 * another output. Cross-platform:
 * - **iOS** — the system route picker (Bluetooth, AirPlay / Sonos-via-AirPlay,
 *   speaker). Always available.
 * - **Android** — the system Output Switcher (Bluetooth / speaker / Cast list) on
 *   Android 11+. No-op on older Android — gate on {@link supportsOutputSwitcher}.
 * - **Web** — no-op.
 */
export function openOutputPicker(): void {
  nativeBrowser.openOutputPicker()
}

/**
 * Whether {@link openOutputPicker} can present a switcher on this device — use it
 * to decide whether to surface the output control in your UI.
 * `true` on iOS and Android 11+ (API 30); `false` on older Android and web.
 */
export function supportsOutputSwitcher(): boolean {
  return nativeBrowser.supportsOutputSwitcher()
}

// MARK: - Getters

/**
 * The current audio output, or `undefined` when unknown. iOS reports one while a
 * session is active; Android reports the active output route via AudioManager
 * (the `type` is coarse — e.g. wired headphones may report as `speaker`). Guard
 * for `undefined`.
 */
export function getOutput(): Output | undefined {
  return nativeBrowser.getOutput()
}

// MARK: - Event Callbacks

/**
 * Subscribes to current-output changes (headphones unplugged, Bluetooth speaker
 * connected, AirPlay/route selected). Fires on iOS and Android 11+; never below.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onOutputChanged = NativeUpdatedValue.emitterize<Output>(
  (cb) => (nativeBrowser.onOutputChanged = cb)
)

// MARK: - Hooks

/**
 * Reactive current audio output; re-renders when it changes (e.g. AirPods
 * connect, a Bluetooth speaker is selected). `undefined` when unknown.
 */
export function useOutput(): Output | undefined {
  return useNativeUpdatedValue(getOutput, onOutputChanged)
}
