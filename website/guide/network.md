---
description: 'Track the device''s internet connectivity with useOnline, getOnline, and onOnlineChanged, and what "online" means on each platform.'
---

# Network

The library tracks the device's internet connectivity with a platform monitor and
exposes it three ways:

- [`useOnline()`](/api/features/network/#useonline) — a reactive hook for your UI.
- [`getOnline()`](/api/features/network/#getonline) — a one-off snapshot.
- [`onOnlineChanged`](/api/features/network/#ononlinechanged) — subscribe to
  changes outside React.

All three report the same boolean, but each platform decides it differently:

- **Android** asks the OS whether the default network offers internet. It does
  not wait for the OS to validate it, so a network that never validates (a
  failing Private DNS, some hotspots) reads `true`, and so can a captive or
  dead Wi-Fi.
- **iOS** reads the system path monitor (`NWPathMonitor`), which reports
  whether a usable network path exists. It does not check that the internet is
  reachable, so a captive or dead Wi-Fi can read `true`.
- **Web** reads `navigator.onLine` and the window's `online` and `offline`
  events, which only say whether a network interface is up.

::: warning Android: only after setupPlayer()
On Android the connectivity monitor belongs to the player, so it runs only once
`setupPlayer()` has connected the player service.

Before that, and while the service restarts, `getOnline()` and `useOnline()`
read `true` even when the device is offline, and `onOnlineChanged` doesn't
fire. Once the service connects, `onOnlineChanged` fires with the current
state. iOS and web report the real state from the start.
:::

## In your UI

`useOnline` re-renders when connectivity flips — enough for an offline banner:

```tsx
import { Text } from 'react-native'
import { useOnline } from 'react-native-audio-browser'

function OfflineBanner() {
  const online = useOnline()
  if (online) return null
  return <Text>You're offline</Text>
}
```

## Outside React

Read a snapshot with `getOnline()`, or subscribe with `onOnlineChanged`
(`addListener` returns an unsubscribe function):

```ts
import { getOnline, onOnlineChanged } from 'react-native-audio-browser'

getOnline() // → true | false

const unsubscribe = onOnlineChanged.addListener((online) => {
  if (online) refetchSomething()
})

// later:
unsubscribe()
```

## You may not need this

The player already reacts to connectivity on its own, so you usually don't wire
these up for playback:

- When the connection drops mid-stream, the now-playing formatter receives
  `stalled: 'offline'` (vs `'buffering'` when online) — see
  [Now Playing → the formatter](/guide/now-playing#the-formatter-derived-continuous).
- On iOS, when the connection returns while the stream sits **stalled** (state
  `'buffering'` with play intent), the player re-establishes it automatically:
  you don't need to listen for reconnect and re-`play()`.

  On Android, most drops surface as a playback **error** instead, and the
  reconnect when connectivity returns only runs with
  [automatic retry](/guide/errors#automatic-retry)
  (`setupPlayer({ retry })`, off by default) enabled. See
  [Live streams → Dropouts](/guide/live-streams#dropouts-stalls-and-retry).

  Once retry gives up, or without it, the player stays in `'error'` until the
  listener (or your code) calls `retry()` or `play()`.

Reach for `useOnline` / `onOnlineChanged` for **your own** UI and data — an
offline banner, disabling a control, refetching a list on reconnect.

## API summary

| API               | Purpose                                                               |
| ----------------- | --------------------------------------------------------------------- |
| `useOnline()`     | Reactive `boolean`; re-renders on connectivity change.                |
| `getOnline()`     | One-off snapshot `boolean` (`true` on Android before setup).          |
| `onOnlineChanged` | Subscribe outside React; `addListener(cb)` returns an unsubscribe fn. |

`true` = online by the platform's definition above; `false` = offline
(including a network with no internet behind it on Android).
