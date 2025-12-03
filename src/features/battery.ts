/**
 * Battery optimization status and warning handling for Android 12+ foreground service restrictions.
 *
 * When an external controller (Bluetooth, car head unit) tries to resume playback while the app
 * is killed and battery restrictions are in place, Android 12+ blocks the foreground service start.
 * This module exposes APIs to detect this condition and guide users to fix their settings.
 */

import { nativePlayer } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

// MARK: - Types

/**
 * Battery optimization status.
 *
 * - `unrestricted`: App can run freely in background. User explicitly allowed it.
 * - `optimized`: System may limit background work (Doze, App Standby). Default state.
 * - `restricted`: User or system severely limited background. Services blocked.
 */
export type BatteryOptimizationStatus =
  | 'unrestricted'
  | 'optimized'
  | 'restricted'

/**
 * Event fired when battery warning pending state changes.
 */
export type BatteryWarningPendingChangedEvent = {
  pending: boolean
}

/**
 * Event fired when battery optimization status changes.
 */
export type BatteryOptimizationStatusChangedEvent = {
  status: BatteryOptimizationStatus
}

// MARK: - Getters

/**
 * Check if a battery warning is pending (Android only).
 * Returns true if a foreground service start was blocked and the user hasn't dismissed
 * the warning or fixed their battery settings.
 * Auto-clears when battery status becomes unrestricted.
 * @returns true if warning is pending, always false on iOS
 */
export function getBatteryWarningPending(): boolean {
  return nativePlayer.getBatteryWarningPending()
}

/**
 * Get the current battery optimization status (Android only).
 * Also fires onBatteryOptimizationStatusChanged if status changed since last call.
 * @returns Current status, always 'unrestricted' on iOS
 */
export function getBatteryOptimizationStatus(): BatteryOptimizationStatus {
  return nativePlayer.getBatteryOptimizationStatus()
}

// MARK: - Actions

/**
 * Dismiss the battery warning without fixing settings (Android only).
 * Call this when the user chooses to ignore the warning.
 * No-op on iOS.
 */
export function dismissBatteryWarning(): void {
  nativePlayer.dismissBatteryWarning()
}

/**
 * Open the system battery settings for this app (Android only).
 * No-op on iOS.
 */
export function openBatterySettings(): void {
  nativePlayer.openBatterySettings()
}

// MARK: - Event Callbacks

/**
 * Subscribes to battery warning pending state changes (Android only).
 * Fires when: failure occurs (true), dismissBatteryWarning() called (false),
 * or status becomes unrestricted (false).
 * Never fires on iOS.
 */
export const onBatteryWarningPendingChanged =
  NativeUpdatedValue.emitterize<BatteryWarningPendingChangedEvent>(
    (cb) => (nativePlayer.onBatteryWarningPendingChanged = cb)
  )

/**
 * Subscribes to battery optimization status changes (Android only).
 * Fires when user returns from settings with a different status.
 * Never fires on iOS.
 */
export const onBatteryOptimizationStatusChanged =
  NativeUpdatedValue.emitterize<BatteryOptimizationStatusChangedEvent>(
    (cb) => (nativePlayer.onBatteryOptimizationStatusChanged = cb)
  )

// MARK: - Hooks

/**
 * Hook that returns whether a battery warning is pending and updates when it changes (Android only).
 * @returns true if warning is pending, always false on iOS
 */
export function useBatteryWarningPending(): boolean {
  return useNativeUpdatedValue(
    getBatteryWarningPending,
    onBatteryWarningPendingChanged,
    'pending'
  )
}

/**
 * Hook that returns the current battery optimization status and updates when it changes (Android only).
 * @returns Current status, always 'unrestricted' on iOS
 */
export function useBatteryOptimizationStatus(): BatteryOptimizationStatus {
  return useNativeUpdatedValue(
    getBatteryOptimizationStatus,
    onBatteryOptimizationStatusChanged,
    'status'
  )
}

/**
 * Return type for useBatteryWarning hook.
 */
export interface BatteryWarning {
  /** Whether a battery warning is pending */
  pending: boolean
  /** Current battery optimization status */
  status: BatteryOptimizationStatus
  /** Dismiss the warning without changing settings */
  dismiss: () => void
  /** Open system battery settings for this app */
  openSettings: () => void
}

/**
 * Meta hook that bundles all battery warning state and actions (Android only).
 * Use this for a complete battery warning UI implementation.
 *
 * @example
 * ```tsx
 * function BatteryWarningBanner() {
 *   const { pending, status, dismiss, openSettings } = useBatteryWarning()
 *
 *   if (!pending) return null
 *
 *   return (
 *     <View>
 *       <Text>Battery status: {status}</Text>
 *       <Button onPress={openSettings} title="Fix" />
 *       <Button onPress={dismiss} title="Dismiss" />
 *     </View>
 *   )
 * }
 * ```
 */
export function useBatteryWarning(): BatteryWarning {
  const pending = useBatteryWarningPending()
  const status = useBatteryOptimizationStatus()

  return {
    pending,
    status,
    dismiss: dismissBatteryWarning,
    openSettings: openBatterySettings
  }
}
