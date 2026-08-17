---
description: 'The whole options surface — setupPlayer, updateOptions, and getOptions — mapped to the feature guide that covers each area in depth.'
---

# Configuration

Everything about how the player behaves is set through three functions:

- [`setupPlayer(options?)`](/api/features/player/#setupplayer) — the one
  declarative call that initializes the player, run once at startup.
- [`updateOptions(options)`](/api/features/player/#updateoptions) — change a
  subset of options later, at runtime.
- [`getOptions()`](/api/features/player/#getoptions) /
  [`useOptions()`](/api/features/player/#useoptions) — read the current,
  resolved options.

This page maps the whole options surface and links to the feature guide that
covers each area in depth.

## `setupPlayer`

Call it once at startup. It's `async`, and applies all options atomically — the
player never exists without its configuration:

```ts
import { setupPlayer } from 'react-native-audio-browser'

await setupPlayer({
  playWhenReady: true,
  repeatMode: 'queue',
  capabilities: { favorite: true },
  progressUpdateEventInterval: 0.5,
  android: { audioContentType: 'music' },
  ios: { category: 'playback' }
})
```

Calling `setupPlayer` again **reconfigures in place**: the fields you pass are
merged over the previous ones, and the audio engine is only rebuilt when a
construction-bound field (buffers, wake mode, …) actually changed. This is also
how you change a [setup-only option](#setup-only-vs-updatable) after launch.

## `updateOptions`

Use it to change runtime options after setup. Every field is optional — pass only
what changes; the values you provide are **merged** over the current ones.

```ts
import { updateOptions } from 'react-native-audio-browser'

updateOptions({ capabilities: { shuffleMode: false } })
updateOptions({ progressUpdateEventInterval: null }) // disable progress events
updateOptions({ ios: { carPlayNowPlayingButtons: ['repeat'] } })
```

`null` is meaningful on exactly two fields, where it turns the feature off:
`progressUpdateEventInterval: null` disables progress events, and
`android.remoteButtonLayout: null` empties the layout (deriving it from
capabilities). Elsewhere, just omit a field to leave it unchanged.

## Setup-only vs updatable

`updateOptions` can change only a subset. The rest are set in `setupPlayer`
(call it again to change them later).

| Option                                                                                   | `setupPlayer` | `updateOptions` |
| ---------------------------------------------------------------------------------------- | :-----------: | :-------------: |
| `capabilities`                                                                           |       ✓       |        ✓        |
| `forwardJumpInterval` / `backwardJumpInterval`                                           |       ✓       |        ✓        |
| `progressUpdateEventInterval`                                                            |       ✓       |        ✓        |
| `android.appKilledPlaybackBehavior` / `skipSilence` / `remoteButtonLayout`               |       ✓       |        ✓        |
| `ios.playbackRates` / `carPlayUpNextButton` / `carPlayNowPlayingButtons`                 |       ✓       |        ✓        |
| `nowPlaying` (metadata formatter)                                                        |       ✓       |        —        |
| `playWhenReady`, `repeatMode` (initial values)                                           |       ✓       |        —        |
| `retry`, `keepSessionAliveOnError`                                                       |       ✓       |        —        |
| `android` / `ios` audio engine & session (`audioContentType`, `wakeMode`, `category`, …) |       ✓       |        —        |

`playWhenReady` and `repeatMode` set the _initial_ state; change them at runtime
with [`setPlayWhenReady`](/guide/playback#play-when-ready) /
[`setRepeatMode`](/guide/queue#repeat-and-shuffle), not `updateOptions`.

## Capabilities

`capabilities` controls which transport controls the system surfaces (lock
screen, notification, CarPlay, Android Auto) expose. **All default to `true`
except `jumpForward`, `jumpBackward`, and `favorite`, which default to `false`.**
Capabilities **merge** — list only the ones you want to change, and the rest keep
their current value:

```ts
updateOptions({
  capabilities: { jumpForward: true, jumpBackward: true } // podcast controls
})
```

| Capability                      | Default | Notes                                          |
| ------------------------------- | ------- | ---------------------------------------------- |
| `play` / `pause` / `stop`       | `true`  | Core transport.                                |
| `seekTo`                        | `true`  | Scrub the timeline.                            |
| `skipToNext` / `skipToPrevious` | `true`  | Move between queue items.                      |
| `jumpForward` / `jumpBackward`  | `false` | Podcast/audiobook seek by interval.            |
| `playbackRate`                  | `true`  | Speed control (Control Center / CarPlay).      |
| `shuffleMode` / `repeatMode`    | `true`  | Shuffle / repeat toggles.                      |
| `favorite`                      | `false` | The heart — see [Favorites](/guide/favorites). |

## Controls & progress

| Option                        | Default | What                                                                                                         |
| ----------------------------- | ------- | ------------------------------------------------------------------------------------------------------------ |
| `forwardJumpInterval`         | `15`    | Seconds for the jump-forward control.                                                                        |
| `backwardJumpInterval`        | `15`    | Seconds for the jump-backward control.                                                                       |
| `progressUpdateEventInterval` | `null`  | Seconds between progress events; `null` disables them — see [Playback → Progress](/guide/playback#progress). |

## Now Playing

`nowPlaying` controls what's published to the now-playing surfaces:

- `true` (default) — publish the track's own title / artist.
- `false` — don't manage now-playing metadata.
- a formatter callback — render every line yourself.

It's **setup-only** (a function can't cross `updateOptions`). See
[Now Playing → the formatter](/guide/now-playing#the-formatter-derived-continuous).

## Android options (`android`)

| Option                      | Default               | What                                                                                                                                                                  |
| --------------------------- | --------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `appKilledPlaybackBehavior` | `'continue-playback'` | What happens when the app is swiped away (`continue-playback` / `pause-playback` / `stop-playback-and-remove-notification`).                                          |
| `skipSilence`               | `false`               | Skip silent segments during playback.                                                                                                                                 |
| `remoteButtonLayout`        | `null`                | Explicit button placement (`back` / `forward` / `overflow`); `null` derives it from capabilities — see [Button layout](/guide/remote-controls#button-layout-android). |
| `audioContentType`          | `'music'`             | Audio attributes content type (setup-only).                                                                                                                           |
| `wakeMode`                  | `'none'`              | CPU/network wake lock during playback (setup-only).                                                                                                                   |

## iOS options (`ios`)

| Option                     | Default                | What                                                                                                  |
| -------------------------- | ---------------------- | ----------------------------------------------------------------------------------------------------- |
| `playbackRates`            | `[0.5, 1.0, 1.5, 2.0]` | Rates offered by the rate control.                                                                    |
| `carPlayUpNextButton`      | `true`                 | Show the "Up Next" button on CarPlay (auto-hidden when the queue has one track).                      |
| `carPlayNowPlayingButtons` | `[]`                   | Buttons on the CarPlay now-playing screen (max **5**, left-to-right) — see [CarPlay](/guide/carplay). |
| `category`                 | _(none)_               | iOS audio session category (setup-only).                                                              |

## Reading the current options

[`getOptions()`](/api/features/player/#getoptions) returns the resolved
[`Options`](/api/features/player/#options) (capabilities, intervals,
`progressUpdateEventInterval`, and the `android` / `ios` blocks for the current
platform). [`useOptions()`](/api/features/player/#useoptions) is the reactive
hook; [`onOptionsChanged`](/api/features/player/#onoptionschanged) is the
subscription.

```tsx
import { useOptions } from 'react-native-audio-browser'

function JumpInterval() {
  const { forwardJumpInterval } = useOptions()
  return <Text>Jump {forwardJumpInterval}s</Text>
}
```

`getOptions` reports the _runtime_ options above — it doesn't echo back the
setup-only `nowPlaying` / `playWhenReady` / `repeatMode` (read those via their
own getters/hooks, e.g. [`useRepeatMode`](/guide/queue#repeat-and-shuffle)).

## API summary

| API                             | Purpose                                                                                                |
| ------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `setupPlayer(options?)`         | Initialize (and reconfigure) the player; `async`, once at startup.                                     |
| `updateOptions(options)`        | Change runtime options later (merged over current); `null` disables on the two fields that support it. |
| `getOptions()` / `useOptions()` | Read the resolved runtime options.                                                                     |
| `onOptionsChanged`              | Subscribe to options changes outside React.                                                            |
