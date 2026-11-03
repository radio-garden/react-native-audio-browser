---
description: 'Read where audio is currently playing (speaker, Bluetooth, AirPlay, the car), react when the route changes, and open the system output switcher.'
---

# Audio Output

Read where audio is currently playing (speaker, Bluetooth, AirPlay, the car…),
react when it changes, and open the system output switcher so the listener can
move playback to another device. Both layers are **cross-platform**:

- **Reading the current output** — `getOutput()` / `useOutput()` /
  `onOutputChanged`. iOS reports the active route while a session is active;
  Android reports the active output route from `AudioManager` (the `type` is
  coarse — e.g. wired headphones may report as `speaker`).
- **Opening the switcher** — `openOutputPicker()` / `supportsOutputSwitcher()`:
  the system route picker on iOS, the system Output Switcher on Android 11+.

::: tip Gate the switcher button on `supportsOutputSwitcher()`
Surface your output button only when `supportsOutputSwitcher()` is `true` — it's
`true` on iOS and Android 11+ (API 30), `false` on older Android and web.
Reading the current output works more broadly, but `getOutput()` can still be
`undefined` (before playback, or when unknown) — so guard for it.
:::

The UI snippets import `Text` / `Button` from `react-native`; everything else is
from `react-native-audio-browser`.

## The current output

[`useOutput()`](/api/features/output/#useoutput) returns the active output and
re-renders whenever it changes (AirPods connect, headphones unplug, a Bluetooth
speaker is selected):

```tsx
import { Text } from 'react-native'
import { useOutput } from 'react-native-audio-browser'

function OutputLabel() {
  const output = useOutput()
  if (!output) return null // not yet known
  return <Text>Playing on {output.name}</Text>
}
```

An [`Output`](/api/features/output/#output) is:

| Field      | Type         | Meaning                                                                         |
| ---------- | ------------ | ------------------------------------------------------------------------------- |
| `name`     | `string`     | Human-readable device name, e.g. `"AirPods Pro"`, `"Kitchen speaker"`.          |
| `type`     | `OutputType` | The output kind (see below).                                                    |
| `external` | `boolean`    | `false` only for the built-in speaker/receiver; `true` for everything external. |

`type` is one of `'speaker'`, `'receiver'` (the iOS earpiece), `'headphones'`,
`'bluetooth'`, `'airplay'`, `'car'`, `'hdmi'`, `'usb'`, `'cast'` (reserved),
`'remote'` (a remote speaker or TV, see below), or `'other'`. Both platforms map their native ports/routes into
this shared set — e.g. iOS's separate Bluetooth ports all collapse to
`'bluetooth'`.

::: warning Android reports a coarser type
On Android the type comes from the system route and is coarser: wired headphones
often report as `'speaker'` (the built-in route), and really only `'bluetooth'`
is reliably distinguished from it. The device **name** still comes through.
:::

### Remote outputs (Android)

With `remoteOutputs: {}` in [`setupPlayer`](/guide/configuration), the system
Output Switcher (Android 11+) also lists the speakers and TVs of media route
providers, and the one the listener selects becomes the output, with
`type: 'remote'`. While one is selected, the switcher offers **Stop casting**
rather than the phone's own outputs; stopping reports the local device again.

The switcher moves the session only for an app that declares AndroidX's
`MediaTransferReceiver` in its own manifest. The library leaves it out, because
it turns on transfer for any MediaRouter in the app, Cast's included:

```xml
<!-- android/app/src/main/AndroidManifest.xml, inside <application> -->
<receiver android:name="androidx.mediarouter.media.MediaTransferReceiver" />
```

Without it, the switcher lists only the phone's own outputs, and the library
logs a warning saying the receiver is missing.

A provider offers its routes by declaring the library's control category,
`com.audiobrowser.RemoteOutputs.CATEGORY`, in their control filters. Only the
selection is reported: the library keeps playing locally, and rendering the
audio on the speaker is the provider's job.

### Google Cast (Android)

With `remoteOutputs: { cast: true }`, selecting a Cast device in the switcher
moves playback there, through Google's Default Media Receiver. The output
reports `type: 'cast'`, and the queue, the position and the play intent move
across both ways: **Stop casting** brings playback back to the phone, at the
same track.

```ts
await setupPlayer({ remoteOutputs: { cast: true } })
```

Cast is opt-in at build time, so apps that don't cast don't ship Google Play
services' Cast framework. Turn it on in the app's `gradle.properties`:

```properties
# android/gradle.properties
AudioBrowser_castEnabled=true
```

The app also declares `MediaTransferReceiver`, as above. Without the build
property, or on a device without Google Play services, playback stays on the
phone and the library logs a warning.

The receiver fetches the stream itself:

- It gets the URL your media transform resolves, without its headers, so a
  stream that needs request headers won't play on it.
- A track without a file extension is sent as `audio/mpeg`; the receiver works
  out the real codec. A track with `live: true` loads as a live stream.
- The receiver shows the track's title, artist and artwork. It doesn't read
  song titles from the stream, so the live song doesn't update while casting.

While casting, what only makes sense on the phone steps aside:

- `setVolume` moves the receiver's volume;
- the equalizer and the skip-silence setting wait for playback to come back;
- the sleep timer pauses the receiver without fading;
- load retries and stall recovery are the receiver's own, and a receiver error
  reaches the app as a playback error.

Known limits:

- No live song titles on the receiver or the phone while casting: the Default
  Media Receiver keeps the metadata it was loaded with.
- No custom or styled receiver; the receiver is Google's Default Media Receiver.
- Android only. On iOS, the route picker lists AirPlay devices.
- A receiver error is not retried; the app decides what to do with it.

Outside React, read a snapshot with `getOutput()` or subscribe with
[`onOutputChanged`](/api/features/output/#onoutputchanged):

```ts
import { getOutput, onOutputChanged } from 'react-native-audio-browser'

getOutput() // Output | undefined

const unsubscribe = onOutputChanged.addListener((output) => {
  console.log('now playing on', output.name)
})

// later:
unsubscribe()
```

## Opening the output picker

[`openOutputPicker()`](/api/features/output/#openoutputpicker) presents the
system output switcher — the AirPlay / Bluetooth / speaker chooser on iOS, and
the Bluetooth / speaker / Cast switcher on Android (11+). Wire it to your own
output button, shown only when
[`supportsOutputSwitcher()`](/api/features/output/#supportsoutputswitcher) is
`true`:

```tsx
import { Button } from 'react-native'
import {
  openOutputPicker,
  supportsOutputSwitcher
} from 'react-native-audio-browser'

function OutputButton() {
  if (!supportsOutputSwitcher()) return null // older Android / web
  return <Button title="Output" onPress={() => openOutputPicker()} />
}
```

`openOutputPicker()` is safe to call unconditionally — it's a no-op where no
switcher exists — but gating the button on `supportsOutputSwitcher()` keeps you
from showing a control that would do nothing.

On Android the switcher is the system one (the picker from the media
notification); selecting a Bluetooth speaker there routes audio at the OS level.
The library can't force playback to a _specific_ Bluetooth device itself — route
selection is the system's job, which is what this hands off to. Sonos appears on
iOS (as an AirPlay target); on Android it isn't a system route, so it won't show.

## You may not need this for unplug handling

When the current output **disappears** — headphones unplugged, a Bluetooth
speaker powered off — the player **pauses automatically** rather than blaring out
of the built-in speaker (the convention every media app follows). It's a
deliberate pause: the listener presses play again when ready. So you don't need
to watch `onOutputChanged` just to handle unplugging — reach for these APIs to
_display_ the output or to _offer_ a switcher.

## API summary

| API                        | Platforms        | Purpose                                                                    |
| -------------------------- | ---------------- | -------------------------------------------------------------------------- |
| `openOutputPicker()`       | iOS, Android 11+ | Present the system output switcher (Bluetooth / AirPlay / speaker / Cast). |
| `supportsOutputSwitcher()` | all              | Whether a switcher can be shown — gate your output button on it.           |
| `useOutput()`              | iOS, Android     | Reactive current output (`Output \| undefined`).                           |
| `getOutput()`              | iOS, Android     | One-off snapshot of the current output.                                    |
| `onOutputChanged`          | iOS, Android     | Subscribe outside React; `addListener(cb)` returns an unsubscribe fn.      |

The whole API is cross-platform; only the switcher needs Android 11+ (gate on
`supportsOutputSwitcher()`), and Android reports coarser output `type`s than iOS.
