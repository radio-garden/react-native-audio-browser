import { useEffect } from 'react'
import type {
  CastConfig,
  CastState,
  CastStateChangedEvent
} from '../specs/audio-browser.nitro'
import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

export type { CastConfig, CastState, CastStateChangedEvent }

// MARK: - Actions

/**
 * Initialise Google Cast. Call once, early. Omit `receiverApplicationId` to use
 * Google's Default Media Receiver. No-op on a build without Cast enabled, and
 * Cast stays inert until this is called.
 */
export function configureCast(config: CastConfig = {}): void {
  nativeBrowser.configureCast(config)
}

/**
 * Present the system Cast device chooser. No-op until `configureCast` is called
 * and on a build without Cast enabled.
 */
export function showCastPicker(): void {
  nativeBrowser.showCastPicker()
}

/** Disconnect the current Cast session; playback hands back to the phone. */
export function endCastSession(): void {
  nativeBrowser.endCastSession()
}

/**
 * Begin active Cast device discovery. Most callers don't need this — the Cast
 * hooks retain discovery automatically while mounted. Use it (paired with
 * `releaseCastDiscovery`) only to drive discovery outside React.
 */
export function retainCastDiscovery(): void {
  nativeBrowser.retainCastDiscovery()
}

/** Release one `retainCastDiscovery`. */
export function releaseCastDiscovery(): void {
  nativeBrowser.releaseCastDiscovery()
}

// MARK: - Getters

/**
 * Current Cast connection state. `'no-devices'` on a non-Cast build or before
 * `configureCast`.
 */
export function getCastState(): CastState {
  return nativeBrowser.getCastState()
}

/** Name of the connected Cast device, or `undefined` when not connected. */
export function getCastDeviceName(): string | undefined {
  return nativeBrowser.getCastDeviceName()
}

/** Whether a Cast session is currently connected. */
export function isCasting(): boolean {
  return nativeBrowser.isCasting()
}

// MARK: - Event Callbacks

/**
 * Subscribe to Cast connection state changes. Never fires on a non-Cast build
 * or before `configureCast`.
 * @returns Cleanup function to unsubscribe.
 */
export const onCastStateChanged =
  NativeUpdatedValue.emitterize<CastStateChangedEvent>(
    (cb) => (nativeBrowser.onCastStateChanged = cb)
  )

// MARK: - Hooks

// Process-wide ref-count so N mounted Cast hooks map to a single native
// active-discovery retain. emitterize installs the native event callback once
// at module load and never signals unsubscribe, so discovery cannot be driven
// off the event subscription — it is driven explicitly by hook mount/unmount.
let discoveryRefCount = 0

function useCastDiscovery(): void {
  useEffect(() => {
    if (discoveryRefCount++ === 0) nativeBrowser.retainCastDiscovery()
    return () => {
      if (--discoveryRefCount === 0) nativeBrowser.releaseCastDiscovery()
    }
  }, [])
}

/** Reactive Cast connection state; re-renders on connect/disconnect. */
export function useCastState(): CastState {
  useCastDiscovery()
  return useNativeUpdatedValue(getCastState, onCastStateChanged)
}

/** Reactive connected Cast device name (`undefined` when not connected). */
export function useCastDeviceName(): string | undefined {
  useCastDiscovery()
  return useNativeUpdatedValue(getCastDeviceName, onCastStateChanged)
}

/** Reactive `isCasting()`. */
export function useIsCasting(): boolean {
  useCastDiscovery()
  return useNativeUpdatedValue(isCasting, onCastStateChanged)
}
