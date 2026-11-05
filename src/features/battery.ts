import { nativeBrowser } from '../native'
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
 * On Android 12+, when a Bluetooth or car controller resumes playback while the
 * app is killed and battery restrictions apply, the system blocks the foreground
 * service start.
 * Returns true if such a start was blocked and the user hasn't dismissed
 * the warning or fixed their battery settings.
 * Auto-clears when battery status becomes unrestricted.
 * @returns true if warning is pending, always false on iOS
 */
export function getBatteryWarningPending(): boolean {
  return nativeBrowser.getBatteryWarningPending()
}

/**
 * Get the current battery optimization status (Android only).
 * @returns Current status, always 'unrestricted' on iOS
 */
export function getBatteryOptimizationStatus(): BatteryOptimizationStatus {
  return nativeBrowser.getBatteryOptimizationStatus()
}

// MARK: - Actions

/**
 * Dismiss the battery warning without fixing settings (Android only).
 * Call this when the user chooses to ignore the warning.
 * No-op on iOS.
 */
export function dismissBatteryWarning(): void {
  nativeBrowser.dismissBatteryWarning()
}

/**
 * Prompt the user to lift this app's battery restriction (Android only).
 * With status `'optimized'` it starts the system's one-tap "ignore battery
 * optimizations" dialog. Android shows that dialog only if the app's manifest
 * declares `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which the
 * library does not declare; without it nothing appears. With any other status it
 * opens the app's App info page.
 * No-op on iOS.
 */
export function openBatterySettings(): void {
  nativeBrowser.openBatterySettings()
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
    (cb) => (nativeBrowser.onBatteryWarningPendingChanged = cb)
  )

/**
 * Subscribes to battery optimization status changes (Android only).
 * Fires when the app comes to the foreground with a different status from the
 * one it had at the previous foreground, typically on return from settings.
 * Never fires on iOS.
 */
export const onBatteryOptimizationStatusChanged =
  NativeUpdatedValue.emitterize<BatteryOptimizationStatusChangedEvent>(
    (cb) => (nativeBrowser.onBatteryOptimizationStatusChanged = cb)
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
  /** Prompt the user to lift the restriction; see {@link openBatterySettings} */
  openSettings: () => void
}

/**
 * Meta hook that bundles all battery warning state and actions (Android only).
 * Use this for a complete battery warning UI implementation.
 *
 * On Android 12+, when a Bluetooth or car controller resumes playback while the
 * app is killed and battery restrictions apply, the system blocks the foreground
 * service start. See the [Battery guide](https://audiobrowser.dev/guide/battery).
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
