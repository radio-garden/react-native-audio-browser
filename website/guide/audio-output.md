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
`'bluetooth'`, `'airplay'`, `'car'`, `'hdmi'`, `'usb'`, `'cast'` (a remote
speaker / TV), or `'other'`. Both platforms map their native ports/routes into
this shared set — e.g. iOS's separate Bluetooth ports all collapse to
`'bluetooth'`.

::: warning Android reports a coarser type
On Android the type comes from the system route and is coarser: wired headphones
often report as `'speaker'` (the built-in route), and really only `'bluetooth'`
is reliably distinguished from it (`'cast'` requires active route discovery,
which the reader doesn't run, so it won't appear here). The device **name** still
comes through.
:::

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
