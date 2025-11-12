import { useEffect } from 'react'
import { Alert, Platform } from 'react-native'
import { useBatteryWarning } from 'react-native-audio-browser'

/**
 * Handles battery warning alerts for Android 12+ foreground service restrictions.
 *
 * Shows an alert when a foreground service start failure is detected (e.g., when
 * Bluetooth or car head unit tries to resume playback while app is in the background
 * and battery restrictions are in place).
 *
 * Battery status changes are detected automatically on the native side when the app
 * comes to foreground, so no AppState handling is needed here.
 *
 * This is a headless component - it renders nothing but manages the alert lifecycle.
 */
export function BatteryWarning() {
  const battery = useBatteryWarning()

  // Show alert when warning is pending
  useEffect(() => {
    if (Platform.OS !== 'android' || !battery.pending) return

    const statusText =
      battery.status === 'restricted'
        ? 'Your battery settings are preventing this app from resuming playback from Bluetooth or your car.'
        : 'Your battery settings may prevent this app from resuming playback from Bluetooth or your car.'

    Alert.alert(
      'Background Playback Issue',
      `${statusText}\n\nTo fix this, tap "Open Settings" and select "Unrestricted" for battery usage.`,
      [
        { text: 'Dismiss', style: 'cancel', onPress: battery.dismiss },
        { text: 'Open Settings', onPress: battery.openSettings }
      ]
    )
  }, [battery.pending, battery.status, battery.dismiss, battery.openSettings])

  return null
}
