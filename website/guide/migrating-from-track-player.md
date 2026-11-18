---
description: 'Porting from react-native-track-player v4: what carries over unchanged, what was renamed or reshaped, and what is new.'
---

# Migrating from react-native-track-player

Most playback and queue calls from
[react-native-track-player](https://github.com/doublesymmetry/react-native-track-player/tree/v4)
(RNTP) v4 keep their names and meaning in `react-native-audio-browser`. This
page lists what changes.

RNTP v5 moved to a
[commercial license](https://github.com/doublesymmetry/react-native-track-player).
`react-native-audio-browser` is MIT.

## What ports over unchanged

These are the same calls with the same meaning. `import` them from
`react-native-audio-browser` instead of `react-native-track-player`:

[`add`](/api/features/queue/#add), [`remove`](/api/features/queue/#remove), [`move`](/api/features/queue/#move),
[`skip`](/api/features/queue/#skip), [`skipToNext`](/api/features/queue/#skiptonext),
[`skipToPrevious`](/api/features/queue/#skiptoprevious), [`setQueue`](/api/features/queue/#setqueue),
[`getQueue`](/api/features/queue/#getqueue), [`getTrack`](/api/features/queue/#gettrack),
[`removeUpcomingTracks`](/api/features/queue/#removeupcomingtracks), [`load`](/api/features/queue/#load),
[`reset`](/api/features/playback/#reset), [`retry`](/api/features/playback/#retry),
[`play`](/api/features/playback/#play), [`pause`](/api/features/playback/#pause),
[`stop`](/api/features/playback/#stop), [`seekTo`](/api/features/playback/#seekto),
[`seekBy`](/api/features/playback/#seekby), [`setRate`](/api/features/playback/#setrate),
[`getRate`](/api/features/playback/#getrate), [`setVolume`](/api/features/playback/#setvolume),
[`getVolume`](/api/features/playback/#getvolume), [`setRepeatMode`](/api/features/queue/#setrepeatmode),
[`getRepeatMode`](/api/features/queue/#getrepeatmode), [`setPlayWhenReady`](/api/features/playback/#setplaywhenready),
[`getPlayWhenReady`](/api/features/playback/#getplaywhenready), [`getActiveTrack`](/api/features/queue/#getactivetrack),
[`getActiveTrackIndex`](/api/features/queue/#getactivetrackindex).

[`setupPlayer`](/api/features/player/#setupplayer) and
[`updateOptions`](/api/features/player/#updateoptions) keep their names, but
their options changed shape. See [Player options](#player-options).

The differences below are what to watch for.

## Getters are synchronous — and there are hooks

RNTP getters return Promises; audio-browser getters return the value
**synchronously**, and most have a reactive **hook**. Drop the `await` (awaiting a
non-Promise returns the value, so this isn't urgent), and prefer the hook in React.

| RNTP v4                       | audio-browser                                                                                                                      |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `await getPosition()`         | [`getProgress().position`](/api/features/playback/#position)                                                                       |
| `await getDuration()`         | [`getProgress().duration`](/api/features/playback/#duration)                                                                       |
| `await getBufferedPosition()` | [`getProgress().buffered`](/api/features/playback/#buffered)                                                                       |
| `await getProgress()`         | [`getProgress()`](/api/features/playback/#getprogress) (sync) / [`usePolledProgress()`](/api/features/playback/#usepolledprogress) |
| `await getState()`            | [`getPlayback().state`](/api/features/playback/#state)                                                                             |
| `await getPlaybackState()`    | [`getPlayback()`](/api/features/playback/#getplayback) / [`usePlayback()`](/api/features/playback/#useplayback)                    |
| `await getActiveTrack()`      | [`getActiveTrack()`](/api/features/queue/#getactivetrack) / [`useActiveTrack()`](/api/features/queue/#useactivetrack)              |
| `await getQueue()`            | [`getQueue()`](/api/features/queue/#getqueue) / [`useQueue()`](/api/features/queue/#usequeue)                                      |
| `await getRepeatMode()`       | [`getRepeatMode()`](/api/features/queue/#getrepeatmode) / [`useRepeatMode()`](/api/features/queue/#userepeatmode)                  |

Two hook gotchas:

- **`useProgress` changed meaning.** RNTP's `useProgress(interval)` is polling;
  its drop-in is [`usePolledProgress(intervalMs)`](/api/features/playback/#usepolledprogress).
  audio-browser _also_ has a [`useProgress()`](/api/features/playback/#useprogress), but it's **event-based and takes no
  interval**. It stays still until `progressUpdateEventInterval` is set.
  TypeScript rejects `useProgress(2000)`; plain JS ignores the argument.
- **`useIsPlaying()` → [`usePlayingState()`](/api/features/playback/#useplayingstate)**,
  but the field is renamed: RNTP's `{ playing, bufferingDuringPlay }` becomes
  `{ playing, buffering }`.

## Events: per-event `on*` / `use*`, no `Event` enum

RNTP centralizes events behind the `Event` enum with `addEventListener` /
`useTrackPlayerEvents`. audio-browser exposes one emitter (and usually a hook)
per event, and `addListener` returns an **unsubscribe function** (not a
subscription with `.remove()`):

```ts
// RNTP
import TrackPlayer, { Event } from 'react-native-track-player'
const sub = TrackPlayer.addEventListener(
  Event.PlaybackActiveTrackChanged,
  (e) => {}
)
sub.remove()

// audio-browser
import { onActiveTrackChanged } from 'react-native-audio-browser'
const unsubscribe = onActiveTrackChanged.addListener((e) => {})
unsubscribe()
```

| RNTP `Event.*`                   | audio-browser                                                                                                                                                                                                                    |
| -------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PlaybackActiveTrackChanged`     | [`onActiveTrackChanged`](/api/features/queue/#onactivetrackchanged) / [`useActiveTrack()`](/api/features/queue/#useactivetrack)                                                                                                  |
| `PlaybackState`                  | [`onPlaybackChanged`](/api/features/playback/#onplaybackchanged) / [`usePlayback()`](/api/features/playback/#useplayback)                                                                                                        |
| `PlaybackProgressUpdated`        | [`onProgressUpdated`](/api/features/playback/#onprogressupdated) / [`useProgress()`](/api/features/playback/#useprogress)                                                                                                        |
| `PlaybackQueueEnded`             | [`onQueueEnded`](/api/features/queue/#onqueueended)                                                                                                                                                                              |
| `PlaybackError` / `PlayerError`  | [`onPlaybackError`](/api/features/errors/#onplaybackerror) / [`usePlaybackError()`](/api/features/errors/#useplaybackerror)                                                                                                      |
| `PlaybackPlayWhenReadyChanged`   | [`onPlayWhenReadyChanged`](/api/features/playback/#onplaywhenreadychanged) / [`usePlayWhenReady()`](/api/features/playback/#useplaywhenready)                                                                                    |
| `Remote*` (Play, Pause, Next, …) | `handleRemote*` to override, `onRemote*` to observe — see [Remote Controls](/guide/remote-controls)                                                                                                                              |
| `Metadata*Received`              | [`onTimedMetadata`](/api/features/metadata/#ontimedmetadata) / [`onChapterMetadata`](/api/features/metadata/#onchaptermetadata) / [`onTrackMetadata`](/api/features/metadata/#ontrackmetadata) — see [Metadata](/guide/metadata) |

## Delete the playback service

RNTP requires `registerPlaybackService(() => require('./service'))` and a service
file that wires `Event.RemotePlay → TrackPlayer.play()`, etc. **audio-browser has
no playback service** — the library wires the native side itself, and remote
controls drive the player by default. Delete the service file and the
registration call.

To customize a remote control, set a
[`handleRemote*`](/guide/remote-controls) override (pass `undefined` to restore
the default); to just observe, use `onRemote*`:

```ts
import { handleRemoteNext, skipToNext } from 'react-native-audio-browser'

// Override "next" (call the default action yourself if you still want it):
handleRemoteNext(() => skipToNext())
handleRemoteNext(undefined) // restore the default
```

Audio interruptions (RNTP's `Event.RemoteDuck`) are handled natively, so the
duck handler in your service can go too. On Android,
`android.audioContentType: 'speech'` pauses on an interruption instead of
ducking.

## `capabilities`: an object, not an array

```ts
// RNTP
import { Capability } from 'react-native-track-player'
await TrackPlayer.updateOptions({
  capabilities: [Capability.Play, Capability.Pause, Capability.SkipToNext]
})

// audio-browser — most are ON by default; specify only what differs
updateOptions({ capabilities: { skipToPrevious: false } })
```

Pass `capabilities` to **either** [`setupPlayer`](/api/features/player/#setupplayer)
(initial) or [`updateOptions`](/api/features/player/#updateoptions) (later) — both
accept it. See
[Configuration → Capabilities](/guide/configuration#capabilities) for the
defaults.
`Capability.Like` / `RatingType` map to the [`favorite`](/guide/favorites)
capability.

## Enums become string literals

audio-browser has no enum objects — use the string values directly:

| RNTP                                 | audio-browser                                 |
| ------------------------------------ | --------------------------------------------- |
| `State.Playing`, `State.Paused`, …   | `'playing'`, `'paused'`, … (same strings)     |
| `RepeatMode.Off` / `Track` / `Queue` | `'off'` / `'track'` / `'queue'`               |
| `Capability.Play` (in an array)      | `{ play: true }` (in the capabilities object) |

`State`'s string values are identical, so `state === 'playing'` checks port
directly — you just drop the enum import.

## `Track`: `url` → `src`

The playable URL field is renamed. In audio-browser, the **playable source**
is [`src`](/api/types/browser-nodes/#src); a track's **browse address** (for the
[browse tree](/guide/browser)) is a separate field, `path`:

<!-- prettier-ignore -->
```ts
// RNTP
{ url: 'https://cdn.example.com/song.mp3', title, artist, artwork }

// audio-browser
{ src: 'https://cdn.example.com/song.mp3', title, artist, artwork }
```

Other field changes:

- `isLiveStream` → [`live`](/api/types/browser-nodes/#live).
- Per-track `headers` / `userAgent` → return them from `media.resolve(track)`
  (or `resolveSync`), which receives each track. Headers that every track
  shares go on the browser's `request` / `media` config
  ([Shaping requests](/guide/browser#shaping-requests)).
- `rating` → favorites (see above).
- `title` is required.
- `Track` has no index signature, so it refuses fields of your own. Keep app
  data in a map keyed by `id` (or by `src`).
- RNTP's `type`, `contentType`, `pitchAlgorithm` and `date` have no `Track`
  field.

`title` / `artist` / `album` / `artwork` / `duration` / `description` /
`genre` are unchanged.

## Now-playing metadata

```ts
// RNTP
TrackPlayer.updateNowPlayingMetadata({ title, artist })
TrackPlayer.clearNowPlayingMetadata()

// audio-browser
import { updateNowPlaying } from 'react-native-audio-browser'
updateNowPlaying({ title, artist })
updateNowPlaying(null) // drop the override; the track's own metadata shows
```

For live streams, prefer the [Now Playing formatter](/guide/now-playing) over
imperative updates — it re-derives the lock-screen lines on every metadata /
state change.

## Player options

`setupPlayer` and `updateOptions` keep `capabilities`,
`forwardJumpInterval`, `backwardJumpInterval` and
`progressUpdateEventInterval`. Other RNTP options moved or have no
equivalent:

| RNTP v4                                                  | audio-browser                                                   |
| -------------------------------------------------------- | --------------------------------------------------------------- |
| `minBuffer` / `maxBuffer` / `backBuffer` / `playBuffer`  | `android.minBuffer` / `maxBuffer` / `backBuffer` / `playBuffer` |
| `minBuffer` on iOS                                       | `ios.buffer`                                                    |
| `maxCacheSize`                                           | `android.maxCacheSize`                                          |
| `androidAudioContentType`                                | `android.audioContentType`                                      |
| `iosCategory` / `iosCategoryMode` / `iosCategoryOptions` | `ios.category` / `ios.categoryMode` / `ios.categoryOptions`     |
| `autoUpdateMetadata`                                     | `nowPlaying`                                                    |
| `notificationCapabilities` / `compactCapabilities`       | none; place Android buttons with `android.remoteButtonLayout`   |
| `icon` / `color`                                         | none                                                            |
| `ratingType`                                             | the `favorite` capability                                       |

The units changed with the names. The four Android buffers and `ios.buffer`
take milliseconds where RNTP took seconds, and `maxCacheSize` takes megabytes
where RNTP took kilobytes, so a value copied across unchanged is off by a
factor of 1000.

The `android` and `ios` keys are typed, so a leftover RNTP option is a type
error. See [Configuration](/guide/configuration) for every option.

## Setup, before and after

```ts
// RNTP — index.js + startup
TrackPlayer.registerPlaybackService(() => require('./service'))
await TrackPlayer.setupPlayer()
await TrackPlayer.updateOptions({
  capabilities: [Capability.Play, Capability.Pause, Capability.SkipToNext],
  progressUpdateEventInterval: 2
})
```

```ts
// audio-browser — one declarative call, no service
import { setupPlayer } from 'react-native-audio-browser'

await setupPlayer({
  progressUpdateEventInterval: 2
  // capabilities are on by default; nothing to register
})
```

See [Configuration](/guide/configuration) for the full options surface and what's
setup-only vs runtime-updatable.

## What you gain

audio-browser adds capabilities RNTP doesn't have — worth adopting once you've
ported:

- A [**browse tree**](/guide/browser) that powers native **CarPlay** and
  **Android Auto** menus (and voice [search](/guide/search)) — see the
  [Automotive overview](/guide/automotive).
- A [**now-playing formatter**](/guide/now-playing) for live-stream metadata and
  transient status lines.
- [**Content gating**](/guide/gate), a [**sleep timer**](/guide/sleep-timer),
  an [**equalizer**](/guide/equalizer), and [**favorites**](/guide/favorites).

## Quick reference

| Area            | RNTP v4                                                | audio-browser                                                                                                              |
| --------------- | ------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------- |
| Getters         | `await TrackPlayer.getX()`                             | `getX()` (sync) or `useX()`                                                                                                |
| Events          | `Event` + `addEventListener` / `useTrackPlayerEvents`  | per-event `onX` / `useX`                                                                                                   |
| Unsubscribe     | `subscription.remove()`                                | the function `addListener` returns                                                                                         |
| Remote handling | a registered playback **service**                      | default behavior + `handleRemote*`                                                                                         |
| Capabilities    | `Capability[]`                                         | `capabilities` object of booleans                                                                                          |
| Enums           | `State.*`, `RepeatMode.*`                              | string literals (`'playing'`, `'queue'`)                                                                                   |
| Playable URL    | `track.url`                                            | [`track.src`](/api/types/browser-nodes/#src)                                                                               |
| Live flag       | `track.isLiveStream`                                   | [`track.live`](/api/types/browser-nodes/#live)                                                                             |
| Now playing     | `updateNowPlayingMetadata` / `clearNowPlayingMetadata` | [`updateNowPlaying`](/api/features/nowPlaying/#updatenowplaying)`(update \| null)`; `null` reverts to the track's metadata |
