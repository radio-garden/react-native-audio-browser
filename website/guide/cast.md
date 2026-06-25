# Google Cast

**Cast** moves *where the audio plays* off the phone and onto a **Cast device**
— a Chromecast, a Nest speaker, a Google TV. While a **Cast session** is
connected, the [Queue](/guide/queue), the active track, and
[Now Playing](/guide/now-playing) keep their meaning; only the **destination**
changes. The player's transport (play / pause / next / seek) drives the Cast
device, and `useActiveTrack()` / `useNowPlaying()` keep working as usual.

::: warning Cast is not the iOS Output / AirPlay route
A Cast device is a **separate player on the network** that fetches the stream
and artwork *itself*. The iOS [Output](/guide/audio-output) route (AirPlay,
Bluetooth, the car) is different: there the phone keeps playing and the system
reroutes the local audio. The egress difference matters — a Cast URL must be
self-contained (query-signed, publicly reachable) because request headers do
**not** cross to the receiver. Keep the two APIs separate; this guide is Cast.
:::

::: tip Sonos is another destination (Android)
On Android, a [Sonos](/guide/sonos) speaker is a second playback destination
behind this same API — it appears in `showCastPicker()` alongside Cast and is
driven by the same hooks. See the [Sonos guide](/guide/sonos) for the Android
permission it needs and its live-only constraints.
:::

Cast is **compiled in by default** and **inert at runtime** until you call
[`configureCast()`](/api/features/cast/#configurecast) — so enabling it is
really just a JS call plus (on iOS) two `Info.plist` keys. Until `configureCast()`
runs, the API degrades to safe defaults (`getCastState()` returns `'no-devices'`,
the hooks return "not casting", `showCastPicker()` is a no-op), so a build that
never casts behaves as if Cast weren't there.

The UI snippets import `Text` / `Button` from `react-native`; everything else is
from `react-native-audio-browser`.

## Turning Cast on

The minimum to cast is: call [`configureCast()`](#configuring-cast-at-runtime)
(below), and on iOS add the two `Info.plist` keys. There's **no build flag to
set** — the Cast SDK is linked by default.

### iOS — local-network permission

The Cast SDK discovers devices over the local network, which Apple gates behind
permission, and the keys can't be injected by the library. Add them to your
app's `Info.plist`:

```xml
<key>NSLocalNetworkUsageDescription</key>
<string>${PRODUCT_NAME} uses the local network to find Cast devices.</string>
<key>NSBonjourServices</key>
<array>
  <string>_googlecast._tcp</string>
  <!-- Add one per custom receiver app id you pass to configureCast(): -->
  <string>_<receiverAppId>._googlecast._tcp</string>
</array>
```

On **iOS 14+** the system shows a one-time local-network permission prompt the
first time discovery runs (i.e. when a Cast hook mounts or you call
[`retainCastDiscovery()`](/api/features/cast/#retaincastdiscovery)). If the
listener denies it, no devices are ever found and `getCastState()` stays
`'no-devices'`.

### Opting out (size-sensitive apps)

The Cast SDK (`google-cast-sdk` on iOS, `play-services-cast-framework` on
Android) is linked by default. An app that will **never** cast can drop it:

- **Android** — `AudioBrowser_enableCast=false` in `android/gradle.properties`.
- **iOS** — `AUDIOBROWSER_DISABLE_CAST=1 pod install`.

Both compile the Cast code to its inert no-op and link no Cast SDK; the JS API
stays present and returns the same safe defaults.

## Configuring Cast at runtime

Call [`configureCast()`](/api/features/cast/#configurecast) once, early (after
`setupPlayer()`). It initialises the Cast SDK and wires up discovery and
session handling. It is idempotent and a no-op on a build without Cast.

```ts
import { configureCast } from 'react-native-audio-browser'

// Google's Default Media Receiver — no receiver app of your own needed.
configureCast()

// Or point at your own styled receiver app:
configureCast({ receiverApplicationId: 'ABCD1234' })
```

Omitting `receiverApplicationId` uses Google's **Default Media Receiver**, which
renders a standard now-playing card with title, subtitle, and artwork. Supply
your own id only when you publish a custom styled receiver; choosing it here
needs no native rebuild. The id is **bound at the first `configureCast()` for
the app's lifetime** — a later call with a different id is ignored (the Cast
context initialises once), so it's a launch-time choice, not a live switch. See
[`CastConfig`](/api/features/cast/#castconfig) for the option shape.

::: warning If your media needs auth, you're not done yet
The Cast device fetches the stream itself, so if your URLs rely on HTTP auth
headers, **nothing will play on the receiver** until you sign auth into the URL.
See [Signing URLs for the receiver](#signing-urls-for-the-receiver) below — it's
the step that makes authenticated audio actually play.
:::

## Reactive Cast state

The hooks are the easiest way to drive your UI; each re-renders on connect /
disconnect and (while mounted) keeps device **discovery** active.

[`useCastState()`](/api/features/cast/#usecaststate) returns the current
[`CastState`](/api/features/cast/#caststate) — one of `'no-devices'`,
`'not-connected'`, `'connecting'`, `'connected'`:

```tsx
import { Text } from 'react-native'
import { useCastState } from 'react-native-audio-browser'

function CastStatus() {
  const state = useCastState()
  if (state === 'no-devices') return null // none on the network (or no Cast)
  return <Text>Cast: {state}</Text>
}
```

`'no-devices'` is **overloaded**: it means Cast is unavailable (a non-Cast build,
or before `configureCast()`) *and* "discovery is on but nothing has been found
yet." The state alone can't tell those apart — if you need to distinguish "Cast
isn't available here" from "no speakers nearby right now," gate on your own
build/platform knowledge, not on this value.

[`useIsCasting()`](/api/features/cast/#useiscasting) is the boolean shortcut for
`state === 'connected'`, and
[`useCastDeviceName()`](/api/features/cast/#usecastdevicename) returns the
connected device's name (or `undefined` when not connected):

```tsx
import { Text } from 'react-native'
import { useCastDeviceName, useIsCasting } from 'react-native-audio-browser'

function CastBadge() {
  const casting = useIsCasting()
  const device = useCastDeviceName()
  if (!casting) return null
  return <Text>Playing on {device}</Text>
}
```

`CastState` is orthogonal to playback state: a `'connected'` session can still be
paused or buffering — read [`usePlayingState()`](/api/features/playback/#useplayingstate)
for that, exactly as you would for local playback.

## Working outside React

For non-React code, read a snapshot or subscribe imperatively. The getters
mirror the hooks: [`getCastState()`](/api/features/cast/#getcaststate),
[`getCastDeviceName()`](/api/features/cast/#getcastdevicename), and
[`isCasting()`](/api/features/cast/#iscasting).

[`onCastStateChanged`](/api/features/cast/#oncaststatechanged) subscribes to
connection changes; `addListener` returns an unsubscribe function:

```ts
import { getCastState, onCastStateChanged } from 'react-native-audio-browser'

getCastState() // CastState

const unsubscribe = onCastStateChanged.addListener((event) => {
  console.log('cast state', event.state, event.deviceName)
})

// later:
unsubscribe()
```

The event payload is a
[`CastStateChangedEvent`](/api/features/cast/#caststatechangedevent)
(`{ state, deviceName }`).

### Discovery is active only while something needs it

Scanning the network for Cast devices costs battery, so the library only does it
while a consumer is listening. The Cast **hooks retain discovery automatically
while mounted** — mount `useCastState()` (or its siblings) somewhere in your
tree and devices appear. Discovery is **not** driven by the
`onCastStateChanged` subscription, so plain `addListener` does not start it.

Outside React, drive discovery explicitly with
[`retainCastDiscovery()`](/api/features/cast/#retaincastdiscovery) and
[`releaseCastDiscovery()`](/api/features/cast/#releasecastdiscovery) — always
paired:

```ts
import {
  retainCastDiscovery,
  releaseCastDiscovery
} from 'react-native-audio-browser'

retainCastDiscovery() // start scanning
// …show a Cast button, wait for a device…
releaseCastDiscovery() // stop scanning when done
```

Retains are ref-counted across both hooks and explicit calls, so the network is
scanned as long as *anything* needs it and stops when the last consumer goes
away. Because hooks share the same counter, **release exactly as many times as
you retain** — an extra `releaseCastDiscovery()` can stop scanning out from under
a mounted hook.

## Drawing your own Cast button

The library is imperative and **ships no native Cast button** — you draw and
style your own, then call
[`showCastPicker()`](/api/features/cast/#showcastpicker) to present the system
device chooser (`MediaRouteChooserDialog` on Android,
`presentCastDialog()` on iOS). This mirrors `openIosOutputPicker()` from the
[Audio Output](/guide/audio-output) guide.

```tsx
import { Button } from 'react-native'
import {
  showCastPicker,
  useCastState
} from 'react-native-audio-browser'

function CastButton() {
  const state = useCastState()
  // Mounting useCastState() keeps discovery active so devices show up.
  if (state === 'no-devices') return null // hide when nothing to cast to
  return <Button title="Cast" onPress={() => showCastPicker()} />
}
```

To disconnect and hand playback back to the phone, call
[`endCastSession()`](/api/features/cast/#endcastsession). `showCastPicker()` is
a no-op until `configureCast()` runs and on a build without Cast, so you don't
have to gate the call by platform.

## Signing URLs for the receiver

A Cast device fetches the stream and artwork over its **own** network egress, so
**per-request headers do not reach it** — only what is baked into the URL does.
The library tells your media / artwork resolution which destination it is
building for via a `target` discriminator: `'local'` for in-process playback
(the default), `'cast'` for the receiver. Branch on it to emit a self-contained,
query-signed URL instead of relying on a header.

The two transforms have **different shapes**, because they carry different
extra context: the **media** transform is route-based, so it gets `routeParams`
and `target` as positional arguments; the **artwork** transform is size-aware,
so it gets a single [`MediaTransformParams`](/api/types/browser/#mediatransformparams)
object carrying `context` (display size) alongside `target`. Match the shape of
whichever one you're writing — don't copy the media signature into `artwork`.

`signUrl` / `authHeader` below are **your own** functions (your CDN signer, your
token source) — the library doesn't provide them.

The **media** `transform` receives `target` as its third argument (after
`request` and `routeParams`):

```ts
import { configureBrowser } from 'react-native-audio-browser'

configureBrowser({
  media: {
    transform: async (request, _routeParams, target) => {
      if (target === 'cast') {
        // The Cast device fetches this itself — sign it into the URL.
        return {
          ...request,
          query: { ...request.query, token: await signUrl(request.path) }
        }
      }
      // Local playback can rely on headers replayed by the DataSource.
      return {
        ...request,
        headers: { ...request.headers, Authorization: await authHeader() }
      }
    }
  }
})
```

The **artwork** `transform` receives a
[`MediaTransformParams`](/api/types/browser/#mediatransformparams) object — note
the object destructure, not positional args — whose `target` field works the
same way (`context` carries the display-size hints, unused here):

```ts
configureBrowser({
  artwork: {
    transform: async ({ request, context, target }) => {
      if (target !== 'cast') return request
      const token = await signUrl(request.path)
      return { ...request, query: { ...request.query, token } }
    }
  }
})
```

See [Browser → Media and artwork](/guide/browser#media-and-artwork) for the full
transform pipeline.

::: tip Now Playing overrides bypass this
A mid-stream `updateNowPlaying()` override sets text directly and does not run
the request pipeline. Bake any auth into URLs you hand the receiver, or use a
signed CDN.
:::

## Limitations

Cast is a mirrored destination, not a second full audio engine. A few things are
deliberately best-effort — see
[ADR&nbsp;0003](https://github.com/radio-garden/react-native-audio-browser/blob/main/docs/adr/0003-google-cast-is-a-mirrored-playback-destination.md)
for the reasoning.

- **Live metadata on the receiver is best-effort.** Rich station metadata and
  artwork are pushed when a track loads. Mid-stream
  [`updateNowPlaying()`](/guide/now-playing) updates push through a cheap
  in-place path — **live on Android**, more **static-ish on iOS** — and
  **never** trigger a stream reload (a reload would drop the live audio).
- **Artwork is not re-signed.** The artwork URL is resolved once at load and not
  reactively refreshed; a broken image on the receiver is treated as a smaller
  harm than dropping audio. The stream URL *is* re-signed reactively (see below).
- **Stream URLs re-sign reactively, not eagerly.** The whole queue is mirrored
  onto the receiver, but signed URLs expire over multi-hour live sessions. When
  the receiver hits a stale-URL load error the library re-resolves *that one
  item* (via your `target: 'cast'` transform) and updates it on the device,
  capped so a genuinely dead stream surfaces a real error instead of looping.
- **Cold relaunch rehydrates the full queue.** A Cast session can outlive the
  app process. On relaunch the library reads each mirrored item back and
  re-resolves the tracks, so the queue and active track come back. The one piece
  that doesn't survive is the in-flight Now Playing *override* (the current ICY
  song title) — it re-arrives from the stream on reconnect.
- **Discovery runs only while a Cast hook is mounted** (or `retainCastDiscovery`
  is held). With nothing listening, `getCastState()` is `'no-devices'` even if a
  device is on the network.
- **EQ is inert and volume routes to the device while casting.** Local DSP
  ([equalizer](/guide/equalizer)) does nothing on audio that isn't on the phone;
  volume controls the Cast device. The [sleep timer](/guide/sleep-timer) still
  fires and pauses the Cast player, but skips its local volume fade.

## API summary

| API | Purpose |
| --- | --- |
| [`configureCast(config?)`](/api/features/cast/#configurecast) | Initialise Cast (call once). No-op on a non-Cast build. |
| [`useCastState()`](/api/features/cast/#usecaststate) | Reactive [`CastState`](/api/features/cast/#caststate); retains discovery while mounted. |
| [`useIsCasting()`](/api/features/cast/#useiscasting) | Reactive "is a session connected". |
| [`useCastDeviceName()`](/api/features/cast/#usecastdevicename) | Reactive connected device name (`undefined` when not). |
| [`getCastState()`](/api/features/cast/#getcaststate) | One-off `CastState` snapshot. |
| [`getCastDeviceName()`](/api/features/cast/#getcastdevicename) | One-off device name. |
| [`isCasting()`](/api/features/cast/#iscasting) | One-off boolean. |
| [`onCastStateChanged`](/api/features/cast/#oncaststatechanged) | Subscribe outside React; does **not** start discovery. |
| [`showCastPicker()`](/api/features/cast/#showcastpicker) | Present the system device chooser. |
| [`endCastSession()`](/api/features/cast/#endcastsession) | Disconnect; hand playback back to the phone. |
| [`retainCastDiscovery()`](/api/features/cast/#retaincastdiscovery) / [`releaseCastDiscovery()`](/api/features/cast/#releasecastdiscovery) | Drive discovery outside React (always paired). |

All degrade safely on a build without Cast and before `configureCast()`.
