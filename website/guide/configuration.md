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
  [`useOptions()`](/api/features/player/#useoptions) — read the current
  options.

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

Calling `setupPlayer` again is how you change a
[setup-only option](#setup-only-vs-updatable) after launch. The runtime options
(capabilities, intervals, the updatable `android` / `ios` fields) carry over on
both platforms. The setup-only fields behave differently on each:

- **Android** reconfigures in place. The fields you pass are merged over the
  previous ones, and the audio engine is only rebuilt when a construction-bound
  field (buffers, wake mode, …) changed. Two fields are exceptions. Left out,
  `remoteOutputs` turns the remote outputs off again, and `nowPlaying` turns
  the default mapping back on. A `nowPlaying` formatter stays until another
  function replaces it: passing `true` or leaving it out does not drop it.
- **iOS** replaces the player, built from that call's options alone. The queue
  is cleared, and a setup-only field you leave out (`ios.category`,
  `ios.buffer`, `retry`, …) goes back to its default, so pass the full `ios` and
  `retry` set each time.

## `updateOptions`

Use it to change runtime options after setup. Every field is optional — pass only
what changes; the values you provide are **merged** over the current ones.

```ts
import { updateOptions } from 'react-native-audio-browser'

updateOptions({ capabilities: { shuffleMode: false } })
updateOptions({ progressUpdateEventInterval: null }) // disable progress events
updateOptions({ ios: { carPlayNowPlayingButtons: ['repeat'] } })
```

`null` means something on two fields:
`progressUpdateEventInterval: null` disables progress events, and
`android.remoteButtonLayout: null` goes back to the layout derived from
capabilities. Elsewhere, omit a field to leave it unchanged.

## Setup-only vs updatable

`updateOptions` can change only a subset. The rest are set in `setupPlayer`
(call it again to change them later; on iOS that call also clears the queue and
resets any setup-only field you omit, see [`setupPlayer`](#setupplayer)).

| Option                                                                                                       | `setupPlayer` | `updateOptions` |
| ------------------------------------------------------------------------------------------------------------ | :-----------: | :-------------: |
| `capabilities`                                                                                               |       ✓       |        ✓        |
| `forwardJumpInterval` / `backwardJumpInterval`                                                               |       ✓       |        ✓        |
| `progressUpdateEventInterval`                                                                                |       ✓       |        ✓        |
| `android.appKilledPlaybackBehavior` / `skipSilence` / `remoteButtonLayout`                                   |       ✓       |        ✓        |
| `ios.playbackRates` / `carPlayUpNextButton` / `carPlayNowPlayingButtons` / `carPlayPlayingIndicatorLocation` |       ✓       |        ✓        |
| `nowPlaying` (metadata formatter)                                                                            |       ✓       |        —        |
| `playWhenReady`, `repeatMode` (initial values)                                                               |       ✓       |        —        |
| `retry`, `keepSessionAliveOnError`, `remoteOutputs`                                                          |       ✓       |        —        |
| `android` / `ios` audio engine & session (`audioContentType`, `wakeMode`, `category`, …)                     |       ✓       |        —        |

`remoteOutputs` (Android) turns on the outputs of the system Output Switcher
beyond the phone's own: `{}` lists media route providers' speakers, and
`{ cast: true, sonos: true }` plays on Cast devices and Sonos rooms. Left out of
a `setupPlayer` call, they are off; see
[Remote outputs](/guide/audio-output#remote-outputs-android).

`retry` retries load errors automatically, and is off by default; see
[Automatic retry](/guide/errors#automatic-retry). `keepSessionAliveOnError`
(Android, default `true`) keeps the media session and its transport controls
through a terminal playback error, so Android Auto keeps next and previous on a
dead stream.

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

| Option                        | Default | What                                                                                                                                                                           |
| ----------------------------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `forwardJumpInterval`         | `15`    | Seconds for the jump-forward control. With no handler, Android seeks by Media3's fixed increment instead; see [Remote Controls](/guide/remote-controls#overriding-a-control).  |
| `backwardJumpInterval`        | `15`    | Seconds for the jump-backward control. With no handler, Android seeks by Media3's fixed increment instead; see [Remote Controls](/guide/remote-controls#overriding-a-control). |
| `progressUpdateEventInterval` | `null`  | Seconds between progress events; `null` disables them — see [Playback → Progress](/guide/playback#progress).                                                                   |

## Now Playing

`nowPlaying` controls what's published to the now-playing surfaces:

- `true` (default) — publish the track's own title / artist.
- `false` — don't manage now-playing metadata.
- a formatter callback — render every line yourself.

It's **setup-only** (a function can't cross `updateOptions`). See
[Now Playing → the formatter](/guide/now-playing#the-formatter-derived-continuous).

## Android options (`android`)

| Option                      | Default                                   | What                                                                                                                                                                  |
| --------------------------- | ----------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `appKilledPlaybackBehavior` | `'stop-playback-and-remove-notification'` | What happens when the app is swiped away (`continue-playback` / `pause-playback` / `stop-playback-and-remove-notification`).                                          |
| `skipSilence`               | `false`                                   | Skip silent segments during playback.                                                                                                                                 |
| `remoteButtonLayout`        | `null`                                    | Explicit button placement (`back` / `forward` / `overflow`); `null` derives it from capabilities — see [Button layout](/guide/remote-controls#button-layout-android). |
| `audioContentType`          | `'music'`                                 | Audio attributes content type (setup-only).                                                                                                                           |
| `wakeMode`                  | `'none'`                                  | CPU/network wake lock during playback (setup-only).                                                                                                                   |
| `handleAudioBecomingNoisy`  | `true`                                    | Pause when the audio becomes noisy, such as when headphones are unplugged (setup-only).                                                                               |
| `maxBuffer`                 | `50000`                                   | Milliseconds of media the player loads ahead (setup-only).                                                                                                            |
| `minBuffer`                 | `50000`                                   | Accepted but has no effect; the player always loads up to `maxBuffer` (setup-only).                                                                                   |
| `playBuffer`                | `1000`                                    | Milliseconds that must be buffered to start or resume after a user action such as a seek (setup-only).                                                                |
| `rebufferBuffer`            | `null`                                    | Milliseconds that must be buffered to resume after a rebuffer; `null` adapts it to how fast the buffer drained (setup-only).                                          |
| `backBuffer`                | `0`                                       | Milliseconds kept behind the playhead (setup-only).                                                                                                                   |
| `maxCacheSize`              | `0`                                       | Cache size in MB (setup-only).                                                                                                                                        |
| `audioOffload`              | `false`                                   | Decode on dedicated hardware to save power (setup-only).                                                                                                              |

## iOS options (`ios`)

| Option                            | Default                | What                                                                                                                                                                               |
| --------------------------------- | ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `playbackRates`                   | `[0.5, 1.0, 1.5, 2.0]` | Rates offered by the rate control.                                                                                                                                                 |
| `carPlayUpNextButton`             | `true`                 | Show the "Up Next" button on CarPlay (auto-hidden when the queue has one track).                                                                                                   |
| `carPlayNowPlayingButtons`        | `[]`                   | Buttons on the CarPlay now-playing screen, left to right, at most one of each kind: see [CarPlay](/guide/carplay#now-playing-buttons).                                             |
| `carPlayPlayingIndicatorLocation` | `'leading'`            | Where the now-playing indicator draws on CarPlay list rows: `'leading'` in the artwork slot, `'trailing'` at the end of the row — see [CarPlay](/guide/carplay#playing-indicator). |
| `category`                        | `'playback'`           | iOS audio session category (setup-only).                                                                                                                                           |
| `categoryMode`                    | `'default'`            | Audio session mode (setup-only).                                                                                                                                                   |
| `categoryOptions`                 | `[]`                   | Audio session category options (setup-only).                                                                                                                                       |
| `categoryPolicy`                  | `'default'`            | Audio session route-sharing policy (setup-only).                                                                                                                                   |
| `buffer`                          | `0`                    | Preferred forward buffer in milliseconds; `0` lets AVPlayer choose (setup-only).                                                                                                   |

## Reading the current options

[`getOptions()`](/api/features/player/#getoptions) returns the current
[`Options`](/api/features/player/#options) (capabilities, intervals,
`progressUpdateEventInterval`, and the `android` / `ios` blocks for the current
platform). `capabilities` holds only the capabilities you set, plus
`jumpForward` / `jumpBackward: false` on Android and iOS. A missing entry means
its default from the [Capabilities](#capabilities) table, so test
`capabilities.play !== false` rather than `capabilities.play === true`.

[`useOptions()`](/api/features/player/#useoptions) is the reactive hook;
[`onOptionsChanged`](/api/features/player/#onoptionschanged) is the
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

| API                             | Purpose                                                            |
| ------------------------------- | ------------------------------------------------------------------ |
| `setupPlayer(options?)`         | Initialize (and reconfigure) the player; `async`, once at startup. |
| `updateOptions(options)`        | Change runtime options later, merged over the current ones.        |
| `getOptions()` / `useOptions()` | Read the current runtime options.                                  |
| `onOptionsChanged`              | Subscribe to options changes outside React.                        |
