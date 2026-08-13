# Hooks

The library exposes its state as **React hooks** — call one in a component and it returns the current value and re-renders whenever that value changes. They keep your in-app UI in sync with playback, the queue, the browse tree, and the device, including changes driven from _outside_ your app (the lock screen, CarPlay, Android Auto, Bluetooth).

```tsx
import { useActiveTrack, usePlayingState } from 'react-native-audio-browser'

function NowPlaying() {
  const track = useActiveTrack()
  const { playing } = usePlayingState()
  return (
    <Text>
      {playing ? 'Playing' : 'Paused'}: {track?.title}
    </Text>
  )
}
```

## The getter / hook / event trio

Most state comes in three forms, so you can read it whichever way fits:

| Form       | Shape    | Use it                                           |
| ---------- | -------- | ------------------------------------------------ |
| **Hook**   | `useX()` | inside a component — re-renders on change        |
| **Getter** | `getX()` | a one-off synchronous read, anywhere             |
| **Event**  | `on…`    | subscribe outside React (returns an unsubscribe) |

Most events are named `on<Thing>Changed`, but several aren't — `onProgressUpdated`, `onPlayingState`, `onPlaybackError`, `onNavigationError`. The exact event for each hook is in the [reference table](#all-hooks-at-a-glance), so don't guess the name.

```ts
import { getPlayback, onPlaybackChanged } from 'react-native-audio-browser'

const now = getPlayback() // read once
const stop = onPlaybackChanged((p) => console.log(p.state)) // subscribe
// stop() to unsubscribe
```

Every hook, getter, and event is a named export from `react-native-audio-browser`. Hooks read a cached value synchronously on first render (no flash of empty state) and then subscribe for you. Mutating state is separate — `setX()` / action functions (`play`, `setQueue`, `setShuffle`, `setRepeatMode`, `setPlayWhenReady`, …) — covered in [Basic Usage](/guide/basic-usage).

## Playback

The transport state — what the player is doing right now.

```tsx
import {
  usePlayback,
  usePlayingState,
  useProgress
} from 'react-native-audio-browser'

function Transport() {
  const { state } = usePlayback() // e.g. 'playing' | 'paused' (full list below)
  const { playing, buffering } = usePlayingState()
  const { position, duration, buffered } = useProgress() // seconds
  return <Scrubber position={position} duration={duration} />
}
```

- [**`usePlayback()`**](/api/features/playback/#useplayback) → `{ state, error? }`. `state` is one of `none`, `ready`, `loading`, `buffering`, `playing`, `paused`, `stopped`, `ended`, `error`.
- [**`usePlayingState()`**](/api/features/playback/#useplayingstate) → `{ playing, buffering }`. A lighter hook when you only need the booleans.
- [**`useProgress()`**](/api/features/playback/#useprogress) → [`Progress`](/api/features/playback/#progress) (`{ position, duration, buffered }`) in seconds. Event-driven, but **off by default**: `progressUpdateEventInterval` is `null` until you set it (in seconds) via `updateOptions({ progressUpdateEventInterval: 0.5 })` — otherwise the value never ticks.
- [**`usePolledProgress(updateInterval = 1000)`**](/api/features/playback/#usepolledprogress) → same [`Progress`](/api/features/playback/#progress), but polled on its own timer (milliseconds) instead of event-driven, so it works without `progressUpdateEventInterval`. Good for a smooth scrubber; it pauses while the app is backgrounded.
- [**`usePlayWhenReady()`**](/api/features/playback/#useplaywhenready) → `boolean`: whether the player will start as soon as content is ready.
- [**`useSystemVolume()`**](/api/features/playback/#usesystemvolume) → `number` 0–1, the device volume (read-only on iOS).

::: tip `useProgress` vs `usePolledProgress`
`useProgress` emits at the global `progressUpdateEventInterval` (and stays still until you set it); `usePolledProgress` ignores that and ticks on its own timer. For a scrubber, `usePolledProgress` is usually the simpler choice.
:::

## Queue and active track

```tsx
import {
  useActiveTrack,
  useQueue,
  useRepeatMode,
  useShuffle
} from 'react-native-audio-browser'

function QueueScreen() {
  const active = useActiveTrack() // Track | undefined
  const queue = useQueue() // Track[]
  const repeat = useRepeatMode() // 'off' | 'track' | 'queue'
  const shuffle = useShuffle() // boolean
  return <List data={queue} highlight={active?.id} />
}
```

[`useActiveTrack`](/api/features/queue/#useactivetrack) updates even when the track changes from an external control (car next/previous), so matching on the track's identity keeps your UI correct. Note `Track.id` is **optional** (and may be undefined for a browse item the user picked in the car without you queuing it) — fall back to `src` / `path` if you rely on `id`. See [Track](/guide/track) for the Track shape.

## Now playing

[**`useNowPlaying()`**](/api/features/nowPlaying/#usenowplaying) → [`NowPlayingMetadata`](/api/features/metadata/#nowplayingmetadata)` | undefined` — the metadata shown on the lock screen and car surfaces. It reflects any override you set via `updateNowPlaying()` (e.g. the current song on a live stream), falling back to the active track otherwise. See [Now Playing](/guide/now-playing).

```tsx
const meta = useNowPlaying()
// { title?, artist?, album?, artwork?, genre?, duration?,
//   description?, mediaId?, elapsedTime? }
```

## Browsing

Mirror the browse tree your app draws — the same state CarPlay and Android Auto render.

- [**`useTabs()`**](/api/features/browser/#usetabs) → [`Track`](/api/types/browser-nodes/#track)`[] | undefined` — the top-level tabs.
- [**`usePath()`**](/api/features/browser/#usepath) → `string | undefined` — the current browse path.
- [**`useContent()`**](/api/features/browser/#usecontent) → [`ResolvedTrack`](/api/types/browser-nodes/#resolvedtrack)` | undefined` — the resolved page (`content?.children` to render).

```tsx
import { useContent, navigate } from 'react-native-audio-browser'

function Browse() {
  const page = useContent()
  return (
    <List data={page?.children ?? []} onSelect={(track) => navigate(track)} />
  )
}
```

See [Browser](/guide/browser) for how navigation and content resolution work.

## Sleep timer

- [**`useSleepTimerActive()`**](/api/features/sleepTimer/#usesleeptimeractive) → `boolean` — lightweight "is a timer set?" check.
- [**`useSleepTimer(params?)`**](/api/features/sleepTimer/#usesleeptimer) → the live [`SleepTimerState`](/api/features/sleepTimer/#sleeptimerstate), or `undefined` when none is set:
  - `{ time, secondsLeft }` for a countdown timer (`time` is the epoch-ms fire time), or
  - `{ sleepWhenPlayedToEnd: true }` for an end-of-track timer.

```tsx
const timer = useSleepTimer({ updateInterval: 1000 })
// timer && 'secondsLeft' in timer → show timer.secondsLeft
```

`params` takes `updateInterval` (countdown tick rate, default 1000ms) and `inactive` (set `true` while backgrounded to pause ticking). `secondsLeft` does not advance in the background.

## Device and environment

| Hook                                                                | Returns                                                 | Notes                                             |
| ------------------------------------------------------------------- | ------------------------------------------------------- | ------------------------------------------------- |
| [`useOnline()`](/api/features/network/#useonline)                   | `boolean`                                               | network connectivity                              |
| [`useCarConnected()`](/api/features/carConnection/#usecarconnected) | `boolean`                                               | CarPlay / Android Auto connected (`false` on web) |
| [`useSystemVolume()`](/api/features/playback/#usesystemvolume)      | `number`                                                | device volume 0–1                                 |
| [`useOutput()`](/api/features/output/#useoutput)                    | [`Output`](/api/features/output/#output)` \| undefined` | current audio output (iOS + Android)              |

```tsx
const output = useOutput() // { type, name, external } | undefined
// e.g. output?.type === 'bluetooth', output?.external === true
```

`useOutput` updates as routes change (AirPods connect, a Bluetooth speaker is selected, etc.); it's `undefined` when unknown, and Android reports coarser `type`s. See [Audio Output](/guide/audio-output).

## Errors

- [**`usePlaybackError()`**](/api/features/errors/#useplaybackerror) → [`PlaybackError`](/api/features/errors/#playbackerror)` | undefined` — playback failures, with a normalized `kind` (`offline`, `unreachable`, `not-found`, `rejected`, `unplayable`, `stalled`, …) to branch on, plus a platform-specific `code` and optional `statusCode` for telemetry. Never show its `message` — see [Errors](/guide/errors#playback-errors).
- [**`useNavigationError()`**](/api/features/errors/#usenavigationerror) → [`NavigationError`](/api/features/errors/#navigationerror)` | undefined` — a browse/search failure, with a typed `code` (`network-error`, `http-error`, `timeout`, `empty-content`, …) and optional `statusCode`.
- [**`useFormattedNavigationError()`**](/api/features/errors/#useformattednavigationerror) → `{ title, message } | undefined` — the same error run through your `formatNavigationError` config (the copy CarPlay and Android Auto show), ready to display.

```tsx
const error = useFormattedNavigationError()
return error ? <Banner title={error.title} body={error.message} /> : null
```

## Player options

[**`useOptions()`**](/api/features/player/#useoptions) → the current [`Options`](/api/features/player/#options) (jump intervals, `capabilities`, and the `android` / `ios` blocks). Mutate with `updateOptions(partial)`.

```tsx
const { forwardJumpInterval, capabilities } = useOptions()
```

## Android-only hooks

**Battery** (Android background-restriction state — all `false`/`unrestricted` on iOS):

- [**`useBatteryWarning()`**](/api/features/battery/#usebatterywarning) → `{ pending, status, dismiss, openSettings }` — the meta-hook for a complete warning UI; `dismiss()` and `openSettings()` are ready-to-wire actions.
- [**`useBatteryWarningPending()`**](/api/features/battery/#usebatterywarningpending) → `boolean` and [**`useBatteryOptimizationStatus()`**](/api/features/battery/#usebatteryoptimizationstatus) → `'unrestricted' | 'optimized' | 'restricted'` are the individual pieces.

```tsx
const { pending, status, dismiss, openSettings } = useBatteryWarning()
```

**Equalizer**: [**`useEqualizerSettings()`**](/api/features/equalizer/#useequalizersettings) → [`EqualizerSettings`](/api/features/equalizer/#equalizersettings)` | undefined` (`bandLevels`, `presets`, `enabled`, …); `undefined` on iOS. Drive it with `setEqualizerEnabled` / `setEqualizerPreset` / `setEqualizerLevels`.

## Debugging

**`useDebug(options?)`** → `{ state, logs, clear }` — a live snapshot of all player state plus a change log (last 100 entries). It's a development aid; `options.enabled` defaults to `__DEV__`.

```tsx
const { state, logs, clear } = useDebug({ metadata: true })
```

## All hooks at a glance

The **Event** column is the emitter name for the getter/event form — handy because the names aren't fully uniform.

The **Hook** name links to its full API entry; the **Returns** type links to its definition where it has one.

| Hook                                                                                  | Returns                                                                          | Event                                | Platform            |
| ------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- | ------------------------------------ | ------------------- |
| [`usePlayback`](/api/features/playback/#useplayback)                                  | `{ state, error? }`                                                              | `onPlaybackChanged`                  | all                 |
| [`usePlayingState`](/api/features/playback/#useplayingstate)                          | `{ playing, buffering }`                                                         | `onPlayingState`                     | all                 |
| [`useProgress`](/api/features/playback/#useprogress)                                  | `{ position, duration, buffered }`                                               | `onProgressUpdated`                  | all                 |
| [`usePolledProgress`](/api/features/playback/#usepolledprogress)                      | `{ position, duration, buffered }`                                               | _(polled)_                           | all                 |
| [`usePlayWhenReady`](/api/features/playback/#useplaywhenready)                        | `boolean`                                                                        | `onPlayWhenReadyChanged`             | all                 |
| [`useSystemVolume`](/api/features/playback/#usesystemvolume)                          | `number`                                                                         | `onSystemVolumeChanged`              | all (read-only iOS) |
| [`useActiveTrack`](/api/features/queue/#useactivetrack)                               | [`Track`](/api/types/browser-nodes/#track)` \| undefined`                        | `onActiveTrackChanged`               | all                 |
| [`useQueue`](/api/features/queue/#usequeue)                                           | [`Track`](/api/types/browser-nodes/#track)`[]`                                   | `onQueueChanged`                     | all                 |
| [`useRepeatMode`](/api/features/queue/#userepeatmode)                                 | [`RepeatMode`](/api/features/queue/#repeatmode)                                  | `onRepeatModeChanged`                | all                 |
| [`useShuffle`](/api/features/queue/#useshuffle)                                       | `boolean`                                                                        | `onShuffleChanged`                   | all                 |
| [`useNowPlaying`](/api/features/nowPlaying/#usenowplaying)                            | `NowPlayingMetadata \| undefined`                                                | `onNowPlayingChanged`                | all                 |
| [`useTabs`](/api/features/browser/#usetabs)                                           | [`Track`](/api/types/browser-nodes/#track)`[] \| undefined`                      | `onTabsChanged`                      | all                 |
| [`usePath`](/api/features/browser/#usepath)                                           | `string \| undefined`                                                            | `onPathChanged`                      | all                 |
| [`useContent`](/api/features/browser/#usecontent)                                     | [`ResolvedTrack`](/api/types/browser-nodes/#resolvedtrack)` \| undefined`        | `onContentChanged`                   | all                 |
| [`useSleepTimerActive`](/api/features/sleepTimer/#usesleeptimeractive)                | `boolean`                                                                        | `onSleepTimerChanged`                | all                 |
| [`useSleepTimer`](/api/features/sleepTimer/#usesleeptimer)                            | [`SleepTimerState`](/api/features/sleepTimer/#sleeptimerstate)` \| undefined`    | `onSleepTimerChanged`                | all                 |
| [`useOnline`](/api/features/network/#useonline)                                       | `boolean`                                                                        | `onOnlineChanged`                    | all                 |
| [`useCarConnected`](/api/features/carConnection/#usecarconnected)                     | `boolean`                                                                        | `onCarConnectedChanged`              | all (`false` web)   |
| [`useOutput`](/api/features/output/#useoutput)                                        | [`Output`](/api/features/output/#output)` \| undefined`                          | `onOutputChanged`                    | iOS, Android        |
| [`usePlaybackError`](/api/features/errors/#useplaybackerror)                          | [`PlaybackError`](/api/features/errors/#playbackerror)` \| undefined`            | `onPlaybackError`                    | all                 |
| [`useNavigationError`](/api/features/errors/#usenavigationerror)                      | [`NavigationError`](/api/features/errors/#navigationerror)` \| undefined`        | `onNavigationError`                  | all                 |
| [`useFormattedNavigationError`](/api/features/errors/#useformattednavigationerror)    | `{ title, message } \| undefined`                                                | `onFormattedNavigationError`         | all                 |
| [`useOptions`](/api/features/player/#useoptions)                                      | [`Options`](/api/features/player/#options)                                       | `onOptionsChanged`                   | all                 |
| [`useBatteryWarning`](/api/features/battery/#usebatterywarning)                       | `{ pending, status, dismiss, openSettings }`                                     | _(composite)_                        | Android             |
| [`useBatteryWarningPending`](/api/features/battery/#usebatterywarningpending)         | `boolean`                                                                        | `onBatteryWarningPendingChanged`     | Android             |
| [`useBatteryOptimizationStatus`](/api/features/battery/#usebatteryoptimizationstatus) | [`BatteryOptimizationStatus`](/api/features/battery/#batteryoptimizationstatus)  | `onBatteryOptimizationStatusChanged` | Android             |
| [`useEqualizerSettings`](/api/features/equalizer/#useequalizersettings)               | [`EqualizerSettings`](/api/features/equalizer/#equalizersettings)` \| undefined` | `onEqualizerChanged`                 | Android             |
| `useDebug`                                                                            | `{ state, logs, clear }`                                                         | _(composite)_                        | all                 |

For the exact return types, see the [API reference](/api/).
