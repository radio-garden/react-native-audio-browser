---
description: 'Detect the Android 12+ battery restrictions that block the foreground service the player needs, and prompt the listener to fix their settings.'
---

# Battery

On Android 12+, if the OS has put your app under battery restrictions, it
**blocks the foreground service** the player needs to start audio in the
background. So when a Bluetooth device or car head unit tries to resume playback
while your app is killed, nothing happens. This feature detects that block and
lets you prompt the user to fix their battery settings.

::: warning Android only
This is an **Android-only** concern (Android 12+ foreground-service limits).
On iOS every getter/hook here is inert: `getBatteryWarningPending()` is always
`false`, `getBatteryOptimizationStatus()` is always `'unrestricted'`, the events
never fire, and `dismissBatteryWarning()` / `openBatterySettings()` are no-ops.
So you can render the banner below unconditionally — it simply never shows on iOS.
:::

The UI snippet imports `View` / `Text` / `Button` from `react-native`; everything
else is from `react-native-audio-browser`.

## The warning banner

A warning becomes **pending** when a background resume is actually blocked. Show
a banner only while `pending` is true, offering to open settings or dismiss.
[`useBatteryWarning()`](/api/features/battery/#usebatterywarning) bundles
everything you need:

```tsx
import { View, Text, Button } from 'react-native'
import { useBatteryWarning } from 'react-native-audio-browser'

function BatteryWarningBanner() {
  const { pending, status, dismiss, openSettings } = useBatteryWarning()
  if (!pending) return null

  return (
    <View>
      <Text>Playback was blocked by battery settings ({status}).</Text>
      <Button title="Fix" onPress={openSettings} />
      <Button title="Dismiss" onPress={dismiss} />
    </View>
  )
}
```

- `openSettings` ([`openBatterySettings`](/api/features/battery/#openbatterysettings))
  opens this app's system battery settings. When the user returns having set the
  app to unrestricted, the warning clears itself.
- `dismiss` ([`dismissBatteryWarning`](/api/features/battery/#dismissbatterywarning))
  hides the warning without changing settings.

## When the warning clears

`pending` goes back to `false` when any of these happens — you don't clear it
manually:

- the user calls `dismiss`, or
- the battery status becomes `'unrestricted'` (e.g. they fixed it in settings).

## Battery optimization status

`status` is the device's current setting for your app:

| Status           | Meaning                                                              |
| ---------------- | -------------------------------------------------------------------- |
| `'unrestricted'` | The user allowed the app to run freely in the background.            |
| `'optimized'`    | Default — the system may limit background work (Doze / App Standby). |
| `'restricted'`   | Background work is severely limited; services are blocked.           |

Background resume is reliable only when `'unrestricted'`, which is why the "Fix"
button sends users to settings to grant it.

## Reading state directly

If you don't want the bundled hook, the pieces are available individually:

```ts
import {
  getBatteryWarningPending,
  getBatteryOptimizationStatus,
  onBatteryWarningPendingChanged,
  onBatteryOptimizationStatusChanged
} from 'react-native-audio-browser'

getBatteryWarningPending() // boolean
getBatteryOptimizationStatus() // 'unrestricted' | 'optimized' | 'restricted'

const off = onBatteryWarningPendingChanged.addListener(({ pending }) => {})
const off2 = onBatteryOptimizationStatusChanged.addListener(({ status }) => {})

// each addListener returns an unsubscribe function:
off()
off2()
```

The reactive hooks [`useBatteryWarningPending()`](/api/features/battery/#usebatterywarningpending)
and [`useBatteryOptimizationStatus()`](/api/features/battery/#usebatteryoptimizationstatus)
wrap the getter+event pairs; `useBatteryWarning()` simply combines both with the
two actions.

## API summary

| API                                                                     | Purpose                                                                |
| ----------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| `useBatteryWarning()`                                                   | Everything for a banner: `{ pending, status, dismiss, openSettings }`. |
| `getBatteryWarningPending()` / `useBatteryWarningPending()`             | Is a warning pending? (Android; `false` on iOS)                        |
| `getBatteryOptimizationStatus()` / `useBatteryOptimizationStatus()`     | Current status (Android; `'unrestricted'` on iOS)                      |
| `onBatteryWarningPendingChanged` / `onBatteryOptimizationStatusChanged` | Subscribe outside React.                                               |
| `openBatterySettings()`                                                 | Open the app's system battery settings (Android).                      |
| `dismissBatteryWarning()`                                               | Dismiss the warning without changing settings (Android).               |

All are **Android-only** — inert (`false` / `'unrestricted'` / no-op) on iOS.
