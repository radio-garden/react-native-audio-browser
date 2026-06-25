# Audio Output

Read where audio is currently playing (speaker, AirPods, AirPlay, car…), react
when it changes, and open the system route picker so the listener can switch
output. Everything here is **iOS-only** — the API names carry an `Ios` prefix as
a reminder.

::: warning iOS only
On Android these are inert: `getIosOutput()` and `useIosOutput()` return
`undefined`, `onIosOutputChanged` never fires, and `openIosOutputPicker()` is a
no-op. So `useIosOutput()` returning `undefined` is also how you detect "not
iOS". On iOS it effectively always returns a value while an audio session is
active — still guard for `undefined` so the same code is safe on Android.
:::

::: tip Not the same as Cast or Sonos
The Output route is a **system audio route**: the phone keeps fetching the
stream and iOS reroutes the local audio (AirPlay, Bluetooth, the car). Google
[Cast](/guide/cast) and [Sonos](/guide/sonos) are different — a separate player
on the network that fetches the stream itself. If you want to move playback onto
a Chromecast, Nest speaker, or Google TV, that's the [Cast guide](/guide/cast);
for a Sonos speaker (Android only) that's the [Sonos guide](/guide/sonos), not
this one.
:::

The UI snippets import `Text` / `Button` from `react-native`; everything else is
from `react-native-audio-browser`.

## The current output

[`useIosOutput()`](/api/features/output/#useiosoutput) returns the active output
and re-renders whenever it changes (AirPods connect, headphones unplug, AirPlay
selected):

```tsx
import { Text } from 'react-native'
import { useIosOutput } from 'react-native-audio-browser'

function OutputLabel() {
  const output = useIosOutput()
  if (!output) return null // Android, or not yet known
  return <Text>Playing on {output.name}</Text>
}
```

An [`IosOutput`](/api/features/output/#iosoutput) is:

| Field | Type | Meaning |
| --- | --- | --- |
| `name` | `string` | Human-readable device name, e.g. `"AirPods Pro"`, `"iPhone Speaker"`. |
| `type` | `IosOutputType` | The port kind (see below). |
| `external` | `boolean` | `false` only for the built-in speaker/receiver; `true` for everything external. |

`type` is one of:

- **Built-in:** `'built-in-speaker'`, `'built-in-receiver'` (the earpiece)
- **Wired:** `'headphones'`, `'usb-audio'`, `'hdmi'`
- **Wireless:** `'airplay'`, `'bluetooth-a2dp'`, `'bluetooth-hfp'`,
  `'bluetooth-le'`
- **Other:** `'car-audio'`, `'other'`

Outside React, read a snapshot with `getIosOutput()` or subscribe with
[`onIosOutputChanged`](/api/features/output/#oniosoutputchanged):

```ts
import { getIosOutput, onIosOutputChanged } from 'react-native-audio-browser'

getIosOutput() // IosOutput | undefined

const unsubscribe = onIosOutputChanged.addListener((output) => {
  console.log('now playing on', output.name)
})

// later:
unsubscribe()
```

## Opening the output picker

[`openIosOutputPicker()`](/api/features/output/#openiosoutputpicker) presents the
system route picker (the AirPlay / Bluetooth / speaker chooser) — wire it to your
own AirPlay button:

```tsx
import { Button } from 'react-native'
import { openIosOutputPicker } from 'react-native-audio-browser'

function AirPlayButton() {
  return <Button title="Output" onPress={() => openIosOutputPicker()} />
}
```

You don't have to gate this call by platform — it's a no-op on Android.

## You may not need this for unplug handling

When the current output **disappears** — headphones unplugged, a Bluetooth
speaker powered off — the player **pauses automatically** rather than blaring out
of the built-in speaker (the convention every media app follows). It's a
deliberate pause: the listener presses play again when ready. So you don't need
to watch `onIosOutputChanged` just to handle unplugging — reach for these APIs to
*display* the output or to *offer* a picker.

## API summary

| API | Purpose |
| --- | --- |
| `useIosOutput()` | Reactive current output (`IosOutput \| undefined`). |
| `getIosOutput()` | One-off snapshot (`undefined` on Android). |
| `onIosOutputChanged` | Subscribe outside React; `addListener(cb)` returns an unsubscribe fn. |
| `openIosOutputPicker()` | Present the system AirPlay/output picker. |

All are **iOS-only** — `undefined` / no-op on Android.
