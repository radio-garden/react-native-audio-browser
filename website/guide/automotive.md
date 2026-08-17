---
description: 'How CarPlay, Android Auto, and Android Automotive all reuse the browse tree, queue, now-playing metadata, and search source you have already built.'
---

# In the car

CarPlay, Android Auto, and Android Automotive all show your content on the car
screen and play through the same player as your app. You don't build a separate
"car app" — the car surfaces are driven by the **same cross-cutting pieces you've
already built**:

- your [browse tree](/guide/browser) becomes the car's browsable menus,
- your [now-playing metadata](/guide/now-playing) fills the car's now-playing
  screen,
- the [player and queue](/guide/playback) are shared, so playback is continuous
  whether the user started it in-app or in the car,
- your [`search` source](/guide/search) answers in-car voice commands
  ("play jazz"),
- [favorites](/guide/favorites) surface a heart and a "play my favorites" intent.

Build those once and they light up every car surface. The per-platform pages
handle the platform-specific **setup**: **[Android Auto](/guide/android-auto)**
(also Android Automotive) and **[CarPlay](/guide/carplay)**.

## Shared vs platform-specific

| Concern                               | Where it's handled                                                                                                       |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| Browse tree / menus                   | Shared — [Browser](/guide/browser)                                                                                       |
| Now-playing metadata & artwork        | Shared — [Now Playing](/guide/now-playing)                                                                               |
| Player, queue, playback               | Shared — [Playback](/guide/playback)                                                                                     |
| Voice search ("play …")               | Shared — your [`search`](/guide/search) source                                                                           |
| Favorites heart & "play my favorites" | Shared — [Favorites](/guide/favorites)                                                                                   |
| App setup, entitlements, manifest     | Per-platform — [Android Auto](/guide/android-auto) / [CarPlay](/guide/carplay)                                           |
| Now-playing buttons                   | Per-platform — [Button layout](/guide/remote-controls#button-layout-android) (Android) / [CarPlay](/guide/carplay) (iOS) |

The takeaway: spend your effort on the shared content model; reach for a platform
page only for that platform's setup and buttons.

## Detecting a car connection

To adapt your in-app UI while connected to a car (a "now playing in your car"
state, a simplified screen), use
[`useCarConnected()`](/api/features/carConnection/#usecarconnected). It's `true`
for a CarPlay connection on iOS, and an Android Auto **or Android Automotive**
connection on Android (`false` on web):

```tsx
import { Text } from 'react-native'
import { useCarConnected } from 'react-native-audio-browser'

function CarBadge() {
  const inCar = useCarConnected()
  if (!inCar) return null
  return <Text>Playing in your car</Text>
}
```

Outside React, read a snapshot with
[`isCarConnected()`](/api/features/carConnection/#iscarconnected) or subscribe
with [`onCarConnectedChanged`](/api/features/carConnection/#oncarconnectedchanged)
(`addListener` returns an unsubscribe function):

```ts
import {
  isCarConnected,
  onCarConnectedChanged
} from 'react-native-audio-browser'

isCarConnected() // boolean

const unsubscribe = onCarConnectedChanged.addListener((connected) => {
  // ...
})
// later: unsubscribe()
```

## Next steps

1. Set up the platform(s) you ship to —
   **[Android Auto](/guide/android-auto)** (and Android Automotive) and/or
   **[CarPlay](/guide/carplay)**.
2. Make sure your [browse tree](/guide/browser) and
   [now-playing](/guide/now-playing) are in good shape — that's what the car
   renders.
3. Wire your [`search` source](/guide/search) so voice commands work in the car.
