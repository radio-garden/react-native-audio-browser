# Migrating from react-native-track-player

If you're coming from [react-native-track-player](https://github.com/doublesymmetry/react-native-track-player/tree/v4)
(RNTP) v4, most of your playback and queue code ports over almost unchanged — the
function names and semantics were deliberately kept familiar. This page maps the
parts that *do* change.

(The two projects aren't affiliated; the API is just intentionally recognizable.)

## What ports over unchanged

These are the same calls with the same meaning — `import` them from
`react-native-audio-browser` instead of `react-native-track-player`:

`setupPlayer`, `updateOptions`, `add`, `remove`, `move`, `skip`, `skipToNext`,
`skipToPrevious`, `setQueue`, `getQueue`, `getTrack`, `removeUpcomingTracks`,
`load`, `reset`, `retry`, `play`, `pause`, `stop`, `seekTo`, `seekBy`, `setRate`,
`getRate`, `setVolume`, `getVolume`, `setRepeatMode`, `getRepeatMode`,
`setPlayWhenReady`, `getPlayWhenReady`, `getActiveTrack`, `getActiveTrackIndex`.

The differences below are what to watch for.

## Getters are synchronous — and there are hooks

RNTP getters return Promises; audio-browser getters return the value
**synchronously**, and most have a reactive **hook**. Drop the `await` (awaiting a
non-Promise still works, so this isn't urgent), and prefer the hook in React.

| RNTP v4 | audio-browser |
| --- | --- |
| `await getPosition()` | [`getProgress()`](/guide/playback#progress)`.position` |
| `await getDuration()` | `getProgress().duration` |
| `await getBufferedPosition()` | `getProgress().buffered` |
| `await getProgress()` | `getProgress()` (sync) / [`usePolledProgress()`](/guide/playback#progress) |
| `await getState()` / `getPlaybackState()` | [`getPlayback()`](/guide/playback#playback-state)`.state` / `usePlayback()` |
| `await getActiveTrack()` | `getActiveTrack()` / [`useActiveTrack()`](/guide/queue#the-active-track) |
| `await getQueue()` | `getQueue()` / [`useQueue()`](/guide/queue#reading-the-queue) |
| `await getRepeatMode()` | `getRepeatMode()` / `useRepeatMode()` |

Two hook gotchas:

- **`useProgress` changed meaning.** RNTP's `useProgress(interval)` is polling;
  its drop-in is [`usePolledProgress(intervalMs)`](/guide/playback#progress).
  audio-browser *also* has a `useProgress()`, but it's **event-based and takes no
  interval** (and needs `progressUpdateEventInterval` enabled) — so copying
  `useProgress(2000)` over compiles but silently ignores the arg. Use
  `usePolledProgress` if you relied on the polling interval.
- **`useIsPlaying()` → [`usePlayingState()`](/guide/playback#just-playing-or-buffering)**,
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

| RNTP `Event.*` | audio-browser |
| --- | --- |
| `PlaybackActiveTrackChanged` | `onActiveTrackChanged` / `useActiveTrack()` |
| `PlaybackState` | `onPlaybackChanged` / `usePlayback()` |
| `PlaybackProgressUpdated` | `onProgressUpdated` / `useProgress()` |
| `PlaybackQueueEnded` | `onQueueEnded` |
| `PlaybackError` / `PlayerError` | `onPlaybackError` / [`usePlaybackError()`](/guide/errors) |
| `PlaybackPlayWhenReadyChanged` | `onPlayWhenReadyChanged` / `usePlayWhenReady()` |
| `Remote*` (Play, Pause, Next, …) | `handleRemote*` to override, `onRemote*` to observe — see [Remote Controls](/guide/remote-controls) |
| `Metadata*Received` | `onTimedMetadata` / `onChapterMetadata` / `onTrackMetadata` — see [Metadata](/guide/metadata) |

## Delete the playback service

RNTP requires `registerPlaybackService(() => require('./service'))` and a service
file that wires `Event.RemotePlay → TrackPlayer.play()`, etc. **audio-browser has
no playback service** — the library wires the native side itself, and remote
controls drive the player by default. Delete the service file and the
registration call. To customize a remote control, set a
[`handleRemote*`](/guide/remote-controls) override (pass `undefined` to restore
the default); to just observe, use `onRemote*`:

```ts
import { handleRemoteNext, skipToNext } from 'react-native-audio-browser'

// Override "next" (call the default action yourself if you still want it):
handleRemoteNext(() => skipToNext())
handleRemoteNext(undefined) // restore the default
```

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

Pass `capabilities` to **either** `setupPlayer` (initial) or `updateOptions`
(later) — both accept it. See
[Configuration → Capabilities](/guide/configuration#capabilities) for the
defaults (everything on except `jumpForward`, `jumpBackward`, `favorite`).
`Capability.Like` / `RatingType` map to the [`favorite`](/guide/favorites)
capability.

## Enums become string literals

audio-browser has no enum objects — use the string values directly:

| RNTP | audio-browser |
| --- | --- |
| `State.Playing`, `State.Paused`, … | `'playing'`, `'paused'`, … (same strings) |
| `RepeatMode.Off` / `Track` / `Queue` | `'off'` / `'track'` / `'queue'` |
| `Capability.Play` (in an array) | `{ play: true }` (in the capabilities object) |

`State`'s string values are identical, so `state === 'playing'` checks port
directly — you just drop the enum import.

## `Track`: `url` → `src`

The playable URL field is renamed. In audio-browser, `url` means a track's
**browse path** (for the [browse tree](/guide/browser)); the **playable source**
is [`src`](/guide/track):

```ts
// RNTP
{ url: 'https://cdn.example.com/song.mp3', title, artist, artwork }

// audio-browser
{ src: 'https://cdn.example.com/song.mp3', title, artist, artwork }
```

Other field changes: `isLiveStream` → [`live`](/guide/track); per-track `headers`
move to a [media request config](/guide/browser); `rating` → favorites (see
above). `title` / `artist` / `album` / `artwork` / `duration` are unchanged.

## Now-playing metadata

```ts
// RNTP
TrackPlayer.updateNowPlayingMetadata({ title, artist })
TrackPlayer.clearNowPlayingMetadata()

// audio-browser
import { updateNowPlaying } from 'react-native-audio-browser'
updateNowPlaying({ title, artist })
updateNowPlaying(null) // clear
```

For live streams, prefer the [Now Playing formatter](/guide/now-playing) over
imperative updates — it re-derives the lock-screen lines on every metadata /
state change.

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

| Area | RNTP v4 | audio-browser |
| --- | --- | --- |
| Getters | `await TrackPlayer.getX()` | `getX()` (sync) or `useX()` |
| Events | `Event` + `addEventListener` / `useTrackPlayerEvents` | per-event `onX` / `useX` |
| Unsubscribe | `subscription.remove()` | the function `addListener` returns |
| Remote handling | a registered playback **service** | default behavior + `handleRemote*` |
| Capabilities | `Capability[]` | `capabilities` object of booleans |
| Enums | `State.*`, `RepeatMode.*` | string literals (`'playing'`, `'queue'`) |
| Playable URL | `track.url` | `track.src` |
| Live flag | `track.isLiveStream` | `track.live` |
| Now playing | `updateNowPlayingMetadata` / `clearNowPlayingMetadata` | `updateNowPlaying(update \| null)` |
