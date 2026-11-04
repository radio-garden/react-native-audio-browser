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
`'bluetooth'`, `'airplay'`, `'car'`, `'hdmi'`, `'usb'`, `'cast'` and `'sonos'`
(see below), `'remote'` (a remote speaker or TV, see below), or `'other'`. Both platforms map their native ports/routes into
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

Each `setupPlayer` sets the remote outputs: a call that leaves `remoteOutputs`
out turns them off, and playback on one comes back to the phone.

::: tip Migrating from `remoteOutputs: true`
`remoteOutputs` was a boolean; it is now an object, with a flag for each output
the library plays on. `remoteOutputs: true` becomes `remoteOutputs: {}`.
:::

A provider offers its routes by declaring the library's control category,
`com.audiobrowser.RemoteOutputs.CATEGORY`, in their control filters. Only the
selection is reported: the library keeps playing locally, and rendering the
audio on the speaker is the provider's job.

### Google Cast (Android)

With `remoteOutputs: { cast: true }`, selecting a Cast device in the switcher
moves playback there, through Google's Default Media Receiver, or the receiver
`castReceiverApplicationId` names. The output
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

The device and its notifications show the receiver's name: "Default Media
Receiver" for Google's. To show the app's own, register a Styled Media Receiver
in the [Google Cast SDK Developer Console](https://cast.google.com/publish) and
pass its ID; Cast takes it once per app process, so a change takes effect after
a restart:

```ts
await setupPlayer({
  remoteOutputs: { cast: true, castReceiverApplicationId: 'ABCD1234' }
})
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
- No custom receiver: the receiver is Google's Default Media Receiver, or a
  Styled Media Receiver, which takes the same media.
- Android only. On iOS, the route picker lists AirPlay devices.
- A receiver error is not retried; the app decides what to do with it.

### Sonos (Android)

With `remoteOutputs: { sonos: true }`, the switcher lists the Sonos rooms on the
local network, and selecting one moves playback there. The output reports
`type: 'sonos'` and the room's name.

```ts
await setupPlayer({ remoteOutputs: { sonos: true } })
```

The app declares `MediaTransferReceiver`, as above, and the permission to hold
a Wi-Fi multicast lock while it looks for rooms. No build property is needed:
the library talks to the speakers itself.

```xml
<!-- android/app/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
```

Without the permission, the library logs a warning and looks for rooms without
the lock, so a room can be missed while the phone saves power on Wi-Fi.

Local network access: the library finds and controls the rooms itself, over
the local network. An app that targets SDK 36 or lower has that access through
`INTERNET`, and needs nothing more. From target SDK 37 (Android 17), Android
blocks the local network until the app is granted the runtime permission
`ACCESS_LOCAL_NETWORK`, which the library doesn't request yet. Without it, rooms
don't appear and can't be controlled. Android exempts apps that use the Output
Switcher for Cast, whose discovery the system runs; its docs don't yet say
whether that covers rooms the library finds itself, so plan for the permission.
See Android's
[local network permission](https://developer.android.com/privacy-and-security/local-network-permission).

::: warning Sonos support is a bonus, not a promise
The library controls the speakers over UPnP, on port 1400 of each speaker. Sonos
calls UPnP unsupported and no longer maintained, and lets the listener switch it
off in the Sonos app (Account › Privacy & Security). When it is off, or Sonos
removes it, the rooms don't appear in the switcher. The library doesn't use
Sonos's cloud or local Control APIs, which need Sonos's approval.
:::

What plays:

- The speaker fetches the stream itself, from the URL your media transform
  resolves, without its headers. A live stream goes as a radio stream: the
  speaker follows redirects and works out the codec, so MP3, AAC and HLS
  streams play, over `https` and plain `http`. A track goes as a file, in a
  format the speaker plays, so the speaker knows its duration and can seek in it.
- The speaker is sent the track's title, artist and artwork to show.
- The speaker reads song titles from the stream (ICY titles, and the ID3 titles
  of HLS streams). The library polls them and reports them through
  `onTimedMetadata`, as it does for local playback, so the live song keeps
  updating.
- Play, pause, skip and the volume act on the speaker. A live stream that
  refuses to pause is stopped instead, and playing it again restarts it.
- A track shows the position and duration the speaker reports.
- Pausing or stopping in the Sonos app shows up as paused here.

Grouping:

- The switcher lists rooms, not speakers. A stereo pair or a home theatre is one
  room.
- Rooms grouped in the Sonos app are listed once, named after the room that
  leads the group: "Kitchen + 2". Playing there plays in every room of the group,
  and the volume is the group's, as the Sonos app's group volume is.
- The library doesn't group or ungroup rooms; the listener does that in the
  Sonos app.

While on Sonos, local-only behaviour steps aside as it does for Cast: the
volume is the speaker's, the equalizer waits for the phone, and a speaker error
reaches the app as a playback error, as does a stream the speaker doesn't start
within 20 seconds and a speaker that stops answering.

Known limits:

- Rooms are found by SSDP multicast on the local network, so the phone and the
  speakers must share it; guest and isolated Wi-Fi networks hide them.
- A track that needs request headers won't play.
- The state is polled every two seconds, so a change made in the Sonos app shows
  up here after a moment.
- If another app or the Sonos app starts something else on the room, this app
  reads as paused and leaves the room alone; playing again loads its own track.
- Android only. On iOS, a Sonos speaker with AirPlay 2 appears in the route
  picker as an AirPlay target.

### On every remote output

Cast and Sonos play the queue the same way:

- The queue stays in the app. The receiver is sent one item at a time, at the
  position it plays from, and the app sends the next when one ends, as the
  repeat mode says. Editing the queue reaches the receiver only when the
  current item changes.
- The shuffle mode is kept and comes back to the phone, but a remote output
  plays the queue in list order
  ([#210](https://github.com/radio-garden/react-native-audio-browser/issues/210)).
- Selecting an output moves the queue, the position, the play intent and the
  repeat mode there. The phone plays on until the current item's URL is
  resolved for the receiver, and the output already reports `'cast'` or
  `'sonos'` meanwhile.
- Moving from one room or Cast device to another goes straight there, without
  the phone in between.
- A receiver that fails to play reaches the app as a playback error with code
  `remote-error`, as does an item whose URL can't be resolved for it, and one
  the receiver hasn't started within 30 seconds. Playing again loads the item
  there anew.

The library's Sonos route provider service is exported, as the Output Switcher
needs to bind it: another app on the phone can bind it too, list the rooms,
select one and set its volume, as through any media route provider.

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
iOS as an AirPlay target, and on Android with `remoteOutputs: { sonos: true }`.

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
