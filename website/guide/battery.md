---
description: 'Detect the Android 12+ battery restrictions that block the foreground service the player needs, and prompt the listener to fix their settings.'
---

# Battery

On Android 12+, if the OS has put your app under battery restrictions, it
**blocks the foreground service** the player needs to start audio in the
background. So when a Bluetooth device or car head unit tries to resume playback
while your app is killed, nothing happens.

This feature detects that block and lets you prompt the user to fix their
battery settings.

::: warning Android only
This is an **Android-only** concern (Android 12+ foreground-service limits).
On iOS every getter/hook here is inert: `getBatteryWarningPending()` is always
`false`, `getBatteryOptimizationStatus()` is always `'unrestricted'`, the events
never fire, and `dismissBatteryWarning()` / `openBatterySettings()` are no-ops.

So you can render the banner below unconditionally: it never shows on iOS.
:::

## The warning banner

A warning becomes **pending** when a background resume is actually blocked. The
block happens while your app is killed, so the library saves the warning and you
see it the next time the app opens. It stays pending across launches until it
clears.

Show a banner only while `pending` is true, offering to open settings or
dismiss. [`useBatteryWarning()`](/api/features/battery/#usebatterywarning)
bundles everything you need:

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
  depends on the status. When it is `'optimized'`, it asks the system for the
  dialog that lets the user exempt the app from battery optimization in one tap.
  This works only if your app declares the permission below.

  With any other status, `'restricted'` included, it opens the app's App info
  page, and the user changes the battery setting from there. When the user
  returns having set the app to unrestricted, the warning clears itself.

- `dismiss` ([`dismissBatteryWarning`](/api/features/battery/#dismissbatterywarning))
  hides the warning without changing settings.

::: warning The one-tap dialog needs a permission
The library doesn't declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Without it,
Android drops the dialog request in the `'optimized'` state: nothing appears,
and the status stays `'optimized'`. The library doesn't detect this or fall
back to another settings page.

To get the dialog, declare the permission in your app:

```xml
<!-- android/app/src/main/AndroidManifest.xml -->
<uses-permission
  android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

Google Play restricts which apps may declare this permission. Check its policy
before you add it. If you can't declare it, send users in the `'optimized'`
state to the system battery settings some other way.
:::

## When the warning clears

`pending` goes back to `false` when any of these happens — you don't clear it
manually:

- your app calls `dismiss` (for example, when the user taps Dismiss), or
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

| API                                                                     | Purpose                                                                                                         |
| ----------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `useBatteryWarning()`                                                   | Everything for a banner: `{ pending, status, dismiss, openSettings }`.                                          |
| `getBatteryWarningPending()` / `useBatteryWarningPending()`             | Is a warning pending? (Android; `false` on iOS)                                                                 |
| `getBatteryOptimizationStatus()` / `useBatteryOptimizationStatus()`     | Current status (Android; `'unrestricted'` on iOS)                                                               |
| `onBatteryWarningPendingChanged` / `onBatteryOptimizationStatusChanged` | Subscribe outside React.                                                                                        |
| `openBatterySettings()`                                                 | Prompt the user to lift the restriction (Android; see above for the permission the `'optimized'` dialog needs). |
| `dismissBatteryWarning()`                                               | Dismiss the warning without changing settings (Android).                                                        |
