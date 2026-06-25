# Sonos

**Sonos** moves *where the audio plays* off the phone and onto a **Sonos**
speaker — a One, a Beam, a Five, a Move. While a Sonos session is connected, the
[Queue](/guide/queue), the [Active Track](/guide/track), and
[Now Playing](/guide/now-playing) keep their meaning; only the **playback
destination** changes. The phone becomes a pure remote: it tells the speaker
which stream URL to fetch and drives play / pause / stop and volume, while the
speaker does the actual fetching and decoding.

Sonos is a **playback destination**, exactly like [Google Cast](/guide/cast) —
not a Bluetooth / AirPlay-style audio route. There is **no Sonos-specific JS
API**: a Sonos speaker is implemented as a custom AndroidX MediaRouteProvider,
so it appears in the *same* system picker as Google Cast and is driven by the
*same* destination API you already use for Cast.

::: warning Sonos is not the iOS Output / AirPlay route
A Sonos speaker is a **separate player on the network** that fetches the stream
and artwork *itself*. The iOS [Output](/guide/audio-output) route (AirPlay,
Bluetooth, the car) is different: there the phone keeps playing and the system
reroutes the local audio. The egress difference matters — a Sonos URL must be
self-contained (query-signed, publicly reachable) because request headers do
**not** cross to the speaker. If you want a system audio *route*, that's the
[Audio Output](/guide/audio-output) guide; this is a *destination*.
:::

::: tip Android only
Sonos support is **Android only**. On iOS the destination API still exists but
covers Google Cast alone — there is no Sonos backend on iOS, and it is out of
scope. The snippets below are safe to run cross-platform: on iOS they simply
never surface a Sonos route in the picker.
:::

The UI snippets import `Text` / `Button` from `react-native`; everything else is
from `react-native-audio-browser`.

## One picker, two backends

On Android, Google Cast is implemented as an AndroidX
[`MediaRouteProvider`](https://developer.android.com/reference/androidx/mediarouter/media/MediaRouteProvider)
and surfaced through the system route chooser. Sonos follows the **identical
pattern**: the library runs SSDP discovery and publishes each speaker it finds
as another route in that same chooser. The practical consequence is that you
write **no Sonos code at all** — you reuse the destination/Cast API verbatim,
and Sonos speakers show up alongside Chromecasts:

- [`showCastPicker()`](/api/features/cast/#showcastpicker) opens the system
  chooser listing **both** Cast devices **and** Sonos speakers.
- [`useCastState()`](/api/features/cast/#usecaststate) /
  [`getCastState()`](/api/features/cast/#getcaststate) /
  [`isCasting()`](/api/features/cast/#iscasting) /
  [`getCastDeviceName()`](/api/features/cast/#getcastdevicename) /
  [`onCastStateChanged`](/api/features/cast/#oncaststatechanged) /
  [`endCastSession()`](/api/features/cast/#endcastsession) /
  [`retainCastDiscovery()`](/api/features/cast/#retaincastdiscovery) all behave
  for a connected Sonos speaker exactly as they do for a Cast device.

::: tip "Cast" naming now covers Sonos
The API keeps the **`Cast`** naming for back-compat, but on Android it now
covers *both* Chromecast and Sonos — a connected Sonos speaker is reported
through `useCastState()` / `getCastDeviceName()` just like a Chromecast. A future
cross-platform rename to a neutral "destination" vocabulary is possible; until
then, read "Cast" in these APIs as "the current playback destination".
:::

Unlike Cast, Sonos needs **no `configureCast()`-style call**. It pulls in no
heavy SDK and is runtime-inert until a destination hook is mounted: discovery
starts when [`useCastState()`](/api/features/cast/#usecaststate) (or an explicit
[`retainCastDiscovery()`](/api/features/cast/#retaincastdiscovery)) is held, and
the speaker scan rides on the same MediaRouter active scan as Cast.

## Setup

The only thing the app must add is one Android permission. There is **no build
flag, no SDK to link, and no runtime permission prompt** for Sonos.

### Android — the multicast permission

SSDP discovery sends a UDP multicast probe on the local network and holds a
`MulticastLock` while scanning. That lock requires the
`CHANGE_WIFI_MULTICAST_STATE` permission. `INTERNET` is already present (the
library needs it to stream), so add only the multicast permission to your app's
`AndroidManifest.xml`:

```xml
<uses-permission
  android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
```

This is a normal (install-time) permission, so there is **no runtime prompt** —
the listener never sees a dialog. Without it, the multicast probe is dropped and
no Sonos speakers are ever discovered (Cast still works, since Cast discovery
does not go through this socket).

### Nothing to initialise

Sonos has no `configureCast()` equivalent. As soon as a destination hook mounts
(or you call [`retainCastDiscovery()`](/api/features/cast/#retaincastdiscovery)),
the MediaRouter active scan starts and the SSDP probe runs alongside it. Mount a
hook somewhere in your tree and nearby speakers appear in the picker.

## Reactive destination state

Drive your UI with the same hooks you use for Cast; each re-renders on connect /
disconnect and (while mounted) keeps device **discovery** active, which is what
makes Sonos speakers appear.

[`useCastState()`](/api/features/cast/#usecaststate) returns the current
[`CastState`](/api/features/cast/#caststate) — one of `'no-devices'`,
`'not-connected'`, `'connecting'`, `'connected'` — for whichever destination
(Cast or Sonos) is in play:

```tsx
import { Text } from 'react-native'
import { useCastState } from 'react-native-audio-browser'

function DestinationStatus() {
  const state = useCastState()
  // none on the network (no Cast device and no Sonos speaker):
  if (state === 'no-devices') return null
  return <Text>Output: {state}</Text>
}
```

[`useIsCasting()`](/api/features/cast/#useiscasting) is the boolean shortcut for
`state === 'connected'`, and
[`useCastDeviceName()`](/api/features/cast/#usecastdevicename) returns the
connected device's name — for Sonos this is the speaker's friendly name (e.g.
`"Kitchen"`), or `undefined` when not connected:

```tsx
import { Text } from 'react-native'
import { useCastDeviceName, useIsCasting } from 'react-native-audio-browser'

function DestinationBadge() {
  const connected = useIsCasting()
  const device = useCastDeviceName()
  if (!connected) return null
  return <Text>Playing on {device}</Text>
}
```

The state is orthogonal to playback state: a `'connected'` Sonos session can
still be paused or buffering — read
[`usePlayingState()`](/api/features/playback/#useplayingstate) for that, exactly
as you would for local playback.

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
  console.log('destination', event.state, event.deviceName)
})

// later:
unsubscribe()
```

### Discovery is active only while something needs it

Scanning the network costs battery, so the library only probes while a consumer
is listening. The **hooks retain discovery automatically while mounted** — mount
[`useCastState()`](/api/features/cast/#usecaststate) (or its siblings) somewhere
in your tree and both Cast devices and Sonos speakers appear. Discovery is
**not** driven by the `onCastStateChanged` subscription, so plain `addListener`
does not start the scan.

Outside React, drive discovery explicitly with
[`retainCastDiscovery()`](/api/features/cast/#retaincastdiscovery) and
[`releaseCastDiscovery()`](/api/features/cast/#releasecastdiscovery) — always
paired:

```ts
import {
  retainCastDiscovery,
  releaseCastDiscovery
} from 'react-native-audio-browser'

retainCastDiscovery() // start scanning (Cast + Sonos)
// …show an output button, wait for a device…
releaseCastDiscovery() // stop scanning when done
```

Retains are ref-counted across both hooks and explicit calls, so the network is
scanned as long as *anything* needs it and stops when the last consumer goes
away. Because hooks share the same counter, **release exactly as many times as
you retain** — an extra `releaseCastDiscovery()` can stop scanning out from
under a mounted hook, hiding both Cast and Sonos.

## Drawing your own output button

The library is imperative and **ships no native picker button** — you draw and
style your own, then call
[`showCastPicker()`](/api/features/cast/#showcastpicker) to present the system
device chooser. On Android that chooser lists Cast devices and Sonos speakers
together; you do nothing extra to include Sonos.

```tsx
import { Button } from 'react-native'
import { showCastPicker, useCastState } from 'react-native-audio-browser'

function OutputButton() {
  const state = useCastState()
  // Mounting useCastState() keeps discovery active so devices show up.
  if (state === 'no-devices') return null // hide when nothing to play to
  return <Button title="Output" onPress={() => showCastPicker()} />
}
```

When the listener picks a Sonos speaker, the library builds the stream URL for
the Active Track, hands it to the speaker, and swaps playback over. To disconnect
and hand playback back to the phone, call
[`endCastSession()`](/api/features/cast/#endcastsession).

## Signing URLs for the speaker

A Sonos speaker fetches the stream and artwork over its **own** network egress,
so **per-request headers do not reach it** — only what is baked into the URL
does. This is the same self-contained-URL constraint as Cast, and it reuses the
same `target` discriminator: the library tells your media / artwork resolution
which destination it is building for. `'local'` is in-process playback (the
default); the receiver target marks a URL the speaker will fetch itself. Branch
on it to emit a self-contained, query-signed URL instead of relying on a header.

The two transforms have **different shapes**, because they carry different extra
context: the **media** transform is route-based, so it gets `routeParams` and
`target` as positional arguments; the **artwork** transform is size-aware, so it
gets a single [`MediaTransformParams`](/api/types/browser/#mediatransformparams)
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
      if (target !== 'local') {
        // The speaker fetches this itself — sign it into the URL.
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
      if (target === 'local') return request
      const token = await signUrl(request.path)
      return { ...request, query: { ...request.query, token } }
    }
  }
})
```

See [Browser → Media and artwork](/guide/browser#media-and-artwork) for the full
transform pipeline, and the [Cast guide](/guide/cast#signing-urls-for-the-receiver)
for the same discriminator in its Cast framing.

::: tip Now Playing overrides bypass this
A mid-stream [`updateNowPlaying()`](/guide/now-playing) override sets text
directly and does not run the request pipeline. Bake any auth into URLs you hand
the speaker, or use a signed CDN.
:::

## How Sonos plays radio

Sonos does not accept a raw MP3 / ICY radio URL on its plain transport — it wants
the `x-rincon-mp3radio://` scheme for that class of endless stream. The library
**rewrites raw MP3 / ICY radio URLs to `x-rincon-mp3radio://` automatically**
before handing them to the speaker; HLS, DASH, and AAC streams play over plain
`http(s)`. The app does nothing special — you provide ordinary stream URLs and
the library picks the scheme the speaker needs.

## Limitations

Sonos is a **live-only**, single-stream destination, not a second full audio
engine. A few things are deliberately constrained:

- **Live-only — no seek, scrubber, or queue on the speaker.** Sonos plays a
  single live stream. The Active Track's URL is handed to the speaker and
  played; there is no next / previous / seek and no duration on the device,
  matching the Sonos live UI with no scrubber. The phone's Queue still keeps its
  meaning as the source of what plays next when you connect.
- **Stream URLs re-sign reactively, not eagerly.** Signed live URLs expire over
  multi-hour sessions. When the speaker reports a stale-URL stop / error the
  library re-resolves the Active Track's URL (via your media `transform`) and
  re-hands it to the speaker, capped so a genuinely dead stream surfaces a real
  error instead of looping forever.
- **Artwork is not re-signed.** Artwork is resolved once when the track loads
  and is not reactively refreshed; a broken image on the speaker is treated as a
  smaller harm than dropping audio.
- **Discovery runs only while a destination hook is mounted** (or
  `retainCastDiscovery` is held). With nothing listening,
  [`getCastState()`](/api/features/cast/#getcaststate) is `'no-devices'` even if
  a speaker is on the network.
- **One active session.** Only one destination plays at a time. Connecting to a
  Sonos speaker ends any active Cast session first, and vice versa.
- **EQ is inert and volume routes to the speaker.** Local DSP
  ([equalizer](/guide/equalizer)) does nothing on audio that isn't on the phone;
  volume controls the Sonos speaker. The [sleep timer](/guide/sleep-timer) still
  fires and pauses the speaker, but skips its local volume fade.
- **System Output Switcher placement is firmware-dependent.** The app chooser
  you open with `showCastPicker()` reliably lists Sonos. Whether a Sonos speaker
  *also* appears in the **system Output Switcher** (the media-notification
  switcher, backed by the MediaRouter2 bridge) depends on the device firmware /
  OEM — promise only the app chooser; treat the system switcher as a bonus and
  verify it on hardware.

## Manual hardware verification checklist

There is **no automated hardware test** for Sonos — the unit suite covers the
SSDP, SOAP, and state-mapping logic with captured fixtures, but a real speaker on
a real network can only be checked by hand. Run this checklist on a device after
any change to the Sonos path:

1. **Discover** — with a destination hook mounted, a Sonos speaker on the same
   Wi-Fi appears (`getCastState()` leaves `'no-devices'`).
2. **Appears in the picker** — `showCastPicker()` lists the speaker alongside any
   Cast devices.
3. **Select → plays** — picking the speaker swaps playback over and live audio
   comes out of the speaker, not the phone.
4. **Play / pause / stop** — the transport controls drive the speaker; state is
   reflected back in `usePlayingState()`.
5. **Volume** — volume changes route to the speaker.
6. **App backgrounded keeps playing** — background or leave the app; the speaker
   keeps playing (the phone is only a remote).
7. **Speaker powered off** — powering the speaker off ends the session and hands
   playback back to the phone (paused, matching the unplug convention).
8. **Multi-hour signed-URL refresh** — leave a signed live stream playing past
   its URL TTL; the library re-signs and re-hands the URL so audio continues.
9. **System Output Switcher (bonus)** — check whether the speaker shows in the
   media-notification switcher; expect this to vary by firmware / OEM.

## API summary

Sonos adds **no new API** — it reuses the destination/Cast surface. On Android,
read these as covering both Chromecast and Sonos.

| API | Purpose |
| --- | --- |
| [`useCastState()`](/api/features/cast/#usecaststate) | Reactive [`CastState`](/api/features/cast/#caststate); retains discovery (Cast + Sonos) while mounted. |
| [`useIsCasting()`](/api/features/cast/#useiscasting) | Reactive "is a destination connected". |
| [`useCastDeviceName()`](/api/features/cast/#usecastdevicename) | Reactive connected device / speaker name (`undefined` when not). |
| [`getCastState()`](/api/features/cast/#getcaststate) | One-off `CastState` snapshot. |
| [`getCastDeviceName()`](/api/features/cast/#getcastdevicename) | One-off device / speaker name. |
| [`isCasting()`](/api/features/cast/#iscasting) | One-off boolean. |
| [`onCastStateChanged`](/api/features/cast/#oncaststatechanged) | Subscribe outside React; does **not** start discovery. |
| [`showCastPicker()`](/api/features/cast/#showcastpicker) | Present the system chooser (Cast + Sonos on Android). |
| [`endCastSession()`](/api/features/cast/#endcastsession) | Disconnect; hand playback back to the phone. |
| [`retainCastDiscovery()`](/api/features/cast/#retaincastdiscovery) / [`releaseCastDiscovery()`](/api/features/cast/#releasecastdiscovery) | Drive discovery outside React (always paired). |

Sonos is **Android only** and requires the `CHANGE_WIFI_MULTICAST_STATE`
permission; on iOS these APIs cover Google Cast alone.
