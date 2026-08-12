# Network

The library tracks the device's internet connectivity with a native monitor and
exposes it three ways:

- [`useOnline()`](/api/features/network/#useonline) — a reactive hook for your UI.
- [`getOnline()`](/api/features/network/#getonline) — a one-off snapshot.
- [`onOnlineChanged`](/api/features/network/#ononlinechanged) — subscribe to
  changes outside React.

All three report the same boolean: `true` when the device has a working internet
connection. **"Online" means *validated* internet, not just "connected to a
network".** Android is strict here — it requires the OS to confirm the network
actually reaches the internet, so Wi-Fi with no real connectivity reads as
`false`. iOS derives the signal from the system path monitor (`NWPathMonitor`),
which is slightly more permissive about such captive networks.

The UI snippet imports `Text` from `react-native`; everything else is from
`react-native-audio-browser`.

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
- When the connection returns, the player retries the stalled stream
  automatically — you don't need to listen for reconnect and re-`play()`.

Reach for `useOnline` / `onOnlineChanged` for **your own** UI and data — an
offline banner, disabling a control, refetching a list on reconnect.

## API summary

| API | Purpose |
| --- | --- |
| `useOnline()` | Reactive `boolean`; re-renders on connectivity change. |
| `getOnline()` | One-off snapshot `boolean`. |
| `onOnlineChanged` | Subscribe outside React; `addListener(cb)` returns an unsubscribe fn. |

`true` = validated internet connection; `false` = offline (including a network
with no real internet on Android).
