# Equalizer

The equalizer lets you shape playback tone — boost the bass, tame the treble —
either by applying a named **preset** (e.g. "Rock", "Jazz") or by setting
**custom band levels** yourself. It wraps the system equalizer effect.

::: warning Android only
The equalizer is **Android-only**. Every getter returns `undefined` and every
setter is a no-op on iOS — Apple exposes no equivalent system equalizer. Always
treat the settings as possibly-absent (see [Availability](#availability)).
:::

The UI snippets below import `View` / `Text` / `Button` from `react-native` and
`Slider` from `@react-native-community/slider`; those import lines are omitted
for brevity. Everything else comes from `react-native-audio-browser`.

## Availability

[`getEqualizerSettings()`](/api/features/equalizer/#getequalizersettings)
returns [`EqualizerSettings | undefined`](/api/features/equalizer/#equalizersettings).
It's `undefined` whenever the equalizer isn't available — on iOS, and **on
Android until the player has an audio session**, which only exists once playback
has started. A settings screen opened before anything has played may see
`undefined`; use the reactive hook so it fills in when playback begins.

## Reading the settings

[`useEqualizerSettings()`](/api/features/equalizer/#useequalizersettings) returns
the current settings and re-renders when they change:

```tsx
import { useEqualizerSettings } from 'react-native-audio-browser'

function EqualizerStatus() {
  const eq = useEqualizerSettings()
  if (!eq) return <Text>Equalizer unavailable</Text>
  return (
    <Text>
      {eq.enabled ? 'On' : 'Off'} · {eq.bandCount} bands
    </Text>
  )
}
```

The [`EqualizerSettings`](/api/features/equalizer/#equalizersettings) object:

| Field                   | Type                  | Meaning                                               |
| ----------------------- | --------------------- | ----------------------------------------------------- |
| `enabled`               | `boolean`             | Whether the effect is currently applied.              |
| `bandCount`             | `number`              | Number of frequency bands.                            |
| `bandLevels`            | `number[]`            | Current level per band, in **millibels**.             |
| `centerBandFrequencies` | `number[]`            | Center frequency per band, in **milliHertz**.         |
| `lowerBandLevelLimit`   | `number`              | Minimum level (millibels) any band accepts.           |
| `upperBandLevelLimit`   | `number`              | Maximum level (millibels) any band accepts.           |
| `presets`               | `string[]`            | Available preset names.                               |
| `activePreset`          | `string \| undefined` | The applied preset, or `undefined` for custom levels. |

See [the units note](#a-note-on-units) — both level and frequency values use
"milli" units.

Outside React, read a snapshot with `getEqualizerSettings()` or subscribe with
[`onEqualizerChanged`](/api/features/equalizer/#onequalizerchanged).

All the `setEqualizer*` calls below are fire-and-forget — they return `void`.
The updated settings come back through `useEqualizerSettings` /
`onEqualizerChanged`, not as a return value.

## Turning it on

The effect does nothing audible until it's enabled. Toggle it with
[`setEqualizerEnabled`](/api/features/equalizer/#setequalizerenabled):

```ts
import { setEqualizerEnabled } from 'react-native-audio-browser'

setEqualizerEnabled(true)
```

## Applying a preset

[`setEqualizerPreset`](/api/features/equalizer/#setequalizerpreset) takes one of
the names from `settings.presets`. The match is case-insensitive; an unknown
name is ignored (a warning is logged), so drive your UI from the actual
`presets` list rather than hard-coded strings:

```tsx
import {
  setEqualizerPreset,
  useEqualizerSettings
} from 'react-native-audio-browser'

function PresetPicker() {
  const eq = useEqualizerSettings()
  if (!eq) return null
  return (
    <View>
      {eq.presets.map((preset) => (
        <Button
          key={preset}
          title={preset}
          onPress={() => setEqualizerPreset(preset)}
        />
      ))}
    </View>
  )
}
```

After applying a preset, `activePreset` reflects it. Available presets come from
the device's system equalizer, so the exact list varies by device.

## Custom band levels

[`setEqualizerLevels`](/api/features/equalizer/#setequalizerlevels) sets every
band at once, in **millibels**. The array length **must equal `bandCount`** — a
mismatched array is ignored — and each value should fall within
`lowerBandLevelLimit … upperBandLevelLimit`:

```ts
import { setEqualizerLevels } from 'react-native-audio-browser'

// Flatten all bands to 0, then boost the lowest band by 6 dB (600 mB).
const flat = new Array(eq.bandCount).fill(0)
flat[0] = 600
setEqualizerLevels(flat)
```

Setting custom levels clears `activePreset` (it becomes `undefined`) — you're no
longer on a named preset. To go back, apply a preset again.

Whichever you choose, the settings persist across track and audio-session
changes: a preset wins over custom levels, and the enabled state always carries
over.

## A complete equalizer screen

Guard for availability, then render an enable toggle, the device presets, and a
slider per band:

```tsx
import { View, Text, Button } from 'react-native'
import Slider from '@react-native-community/slider'
import {
  useEqualizerSettings,
  setEqualizerEnabled,
  setEqualizerPreset,
  setEqualizerLevels
} from 'react-native-audio-browser'

// Center frequencies are milliHertz; levels are millibels (1/100 dB).
const formatHz = (mHz: number) => {
  const hz = mHz / 1000
  if (hz < 1000) return `${hz.toFixed(0)} Hz`
  return `${(hz / 1000).toFixed(hz >= 10000 ? 0 : 1)} kHz`
}

function EqualizerScreen() {
  const eq = useEqualizerSettings()
  if (!eq) return <Text>Equalizer not available</Text>

  const setBand = (index: number, millibels: number) => {
    const next = [...eq.bandLevels]
    next[index] = millibels
    setEqualizerLevels(next)
  }

  return (
    <View>
      <Button
        title={eq.enabled ? 'Disable' : 'Enable'}
        onPress={() => setEqualizerEnabled(!eq.enabled)}
      />

      {eq.presets.map((preset) => (
        <Button
          key={preset}
          title={preset === eq.activePreset ? `• ${preset}` : preset}
          onPress={() => setEqualizerPreset(preset)}
        />
      ))}

      {eq.bandLevels.map((level, index) => (
        <View key={eq.centerBandFrequencies[index]}>
          <Text>
            {formatHz(eq.centerBandFrequencies[index])}:{' '}
            {(level / 100).toFixed(1)} dB
          </Text>
          <Slider
            minimumValue={eq.lowerBandLevelLimit}
            maximumValue={eq.upperBandLevelLimit}
            value={level}
            // Commit on release; onValueChange fires continuously.
            onSlidingComplete={(millibels) => setBand(index, millibels)}
          />
        </View>
      ))}
    </View>
  )
}
```

Note: `@react-native-community/slider`'s `value` is the _initial_ value only —
the slider keeps its own state after mounting. That's fine here, but if you want
the thumbs to jump when a preset is applied, give each `Slider` a
`key={eq.activePreset}` so it remounts with the new levels.

## A note on units

The system equalizer works in "milli" units, so convert for display:

- **Levels** (`bandLevels`, the limits, and what you pass to
  `setEqualizerLevels`) are in **millibels** — hundredths of a decibel. `600`
  mB = 6 dB; divide by 100 for dB.
- **Center frequencies** (`centerBandFrequencies`) are in **milliHertz**.
  `60000` = 60 Hz; divide by 1000 for Hz.

## API summary

| API                                                 | Purpose                                       |
| --------------------------------------------------- | --------------------------------------------- |
| `useEqualizerSettings()` / `getEqualizerSettings()` | Read settings (`undefined` if unavailable).   |
| `onEqualizerChanged`                                | Subscribe to settings changes outside React.  |
| `setEqualizerEnabled(bool)`                         | Turn the effect on or off.                    |
| `setEqualizerPreset(name)`                          | Apply a preset from `settings.presets`.       |
| `setEqualizerLevels(millibels[])`                   | Set all bands; length must equal `bandCount`. |

All are **Android-only** — no-ops / `undefined` on iOS.
