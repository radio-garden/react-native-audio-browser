# Playback

This guide covers **controlling the active track** — transport (play / pause /
stop), seeking, playback state, progress, rate, and volume. It's the toolkit for
building your own player UI.

Playback acts on whatever the **active track** is. _Which_ track that is — and
moving between tracks with next/previous — belongs to the [queue](/guide/basic-usage#the-queue);
this page is about playing the one that's already active. The system surfaces
(lock screen, notification, car) are covered in [Now Playing](/guide/now-playing).

> All functions here are synchronous and safe to call before a track is loaded —
> they're no-ops or update intent rather than throwing.

The UI snippets below import `useState` from `react`, `Text` / `Button` /
`Switch` from `react-native`, and `Slider` from
`@react-native-community/slider`; those import lines are omitted for brevity.
Everything else comes from `react-native-audio-browser`.

## Transport controls

```ts
import { play, pause, togglePlayback, stop } from 'react-native-audio-browser'

play() // play or resume the active track
pause() // pause, keeping position
togglePlayback() // flip between play and pause
stop() // stop and reset position to the start
```

`stop` keeps the track loaded — `play()` afterwards starts it again from the
beginning. (For live streams there's no position to reset, so it just stops.)
That's different from `reset`, which stops playback **and clears the queue**:

```ts
import { reset } from 'react-native-audio-browser'

reset() // tear down: stop + empty the queue
```

|           | Position       | Track stays loaded | Queue   |
| --------- | -------------- | ------------------ | ------- |
| `pause()` | kept           | yes                | kept    |
| `stop()`  | reset to start | yes                | kept    |
| `reset()` | —              | no                 | cleared |

## Seeking

Seek within the active track by an absolute position or a relative offset, both
in **seconds**:

```ts
import { seekTo, seekBy } from 'react-native-audio-browser'

seekTo(90) // jump to 1:30
seekBy(30) // skip ahead 30s
seekBy(-15) // back 15s
```

For live streams, jump back to the live edge (a no-op for non-live tracks):

```ts
import { seekToLiveEdge } from 'react-native-audio-browser'

seekToLiveEdge()
```

Moving between tracks (next / previous / skip) is part of the
[queue](/guide/basic-usage#the-queue), not seeking.

## Playback state

`usePlayback` returns the current `{ state, error }`. The `state` is one of:

| State         | Meaning                                        |
| ------------- | ---------------------------------------------- |
| `'none'`      | Idle — no track loaded (initial state).        |
| `'loading'`   | Loading the item before playback can begin.    |
| `'ready'`     | Track loaded and ready, currently paused.      |
| `'playing'`   | Currently playing.                             |
| `'buffering'` | Loading more data before it can continue.      |
| `'paused'`    | Paused.                                        |
| `'stopped'`   | Stopped.                                       |
| `'ended'`     | Reached the end of the queue.                  |
| `'error'`     | Playback failed — see [Errors](/guide/errors). |

```tsx
import { usePlayback, retry } from 'react-native-audio-browser'

function PlayerStatus() {
  const { state } = usePlayback() // `error` is also here — see Errors

  if (state === 'error') {
    return <Button title="Retry" onPress={() => retry()} />
  }
  return <Text>{state}</Text>
}
```

`retry()` only does something while the state is `'error'`; it re-attempts the
current item. See [Errors](/guide/errors) for the error shape and handling.

### Just playing or buffering?

For a play/pause button, drive it off `usePlayingState` — not
`state === 'playing'` — so it isn't thrown off by the `loading`/`buffering`
states. It gives you two booleans and re-renders only when they flip:

```tsx
import { usePlayingState, togglePlayback } from 'react-native-audio-browser'

function PlayPauseButton() {
  const { playing, buffering } = usePlayingState()

  return (
    <Button
      title={buffering ? 'Loading…' : playing ? 'Pause' : 'Play'}
      onPress={() => togglePlayback()}
    />
  )
}
```

### Play-when-ready

`playWhenReady` is the **intent** to play, separate from whether audio is
actually coming out yet (it may still be loading or buffering). Setting it is
equivalent to calling `play()` / `pause()`:

```tsx
import { setPlayWhenReady, usePlayWhenReady } from 'react-native-audio-browser'

setPlayWhenReady(true) // same as play()

function PlayToggle() {
  const wantsToPlay = usePlayWhenReady()
  return (
    <Switch
      value={wantsToPlay}
      onValueChange={(next) => setPlayWhenReady(next)}
    />
  )
}
```

Use `usePlayWhenReady` when your control should reflect the user's _intent_
immediately (the toggle flips the instant they tap, even while buffering); use
`usePlayingState` when it should reflect whether sound is actually playing.

## Progress

Progress is `{ position, duration, buffered }`, all in seconds. There are two
hooks, and the difference matters:

- **`usePolledProgress(intervalMs?)`** — polls on a timer (default 1000 **ms**).
  Works out of the box, and automatically pauses while the app is backgrounded.
  **Start here.**
- **`useProgress()`** — driven by native progress events. These are
  **disabled by default** (`progressUpdateEventInterval` is `null`), so this
  hook won't update until you turn them on (see below).

> **Mind the units.** `usePolledProgress` takes its interval in
> **milliseconds**; `progressUpdateEventInterval` (below) is in **seconds**.

```tsx
import { usePolledProgress, seekTo } from 'react-native-audio-browser'

function Scrubber() {
  const { position, duration } = usePolledProgress()

  return (
    <Slider
      minimumValue={0}
      maximumValue={duration || 1}
      value={position}
      // Seek when the user releases the thumb.
      onSlidingComplete={(seconds) => seekTo(seconds)}
    />
  )
}
```

To use the event-based `useProgress` instead, enable progress events once at
setup by setting how often they fire (in seconds):

```ts
import { setupPlayer } from 'react-native-audio-browser'

await setupPlayer({ progressUpdateEventInterval: 0.5 })
// or later: updateOptions({ progressUpdateEventInterval: 0.5 })
```

Outside React, `getProgress()` returns a one-off snapshot of the current
position/duration/buffered — it's a plain getter and is **never** gated, so it
works even with progress events off. Only the `onProgressUpdated` event (and
therefore `useProgress`) depends on `progressUpdateEventInterval`.

## Playback rate

Rate is a multiplier: `1` is normal, `0.5` half speed, `2` double. Handy for
podcasts and audiobooks:

```ts
import { getRate, setRate } from 'react-native-audio-browser'

setRate(1.5) // 1.5× speed
getRate() // → current rate
```

There's no reactive hook for rate (it only changes when you set it), so a
control that displays the current speed just holds it in local state:

```tsx
function SpeedControl() {
  const [rate, setRateState] = useState(() => getRate())
  const cycle = () => {
    const next = rate >= 2 ? 0.5 : rate + 0.5
    setRate(next) // tell the player
    setRateState(next) // update the label
  }
  return <Button title={`${rate.toFixed(1)}×`} onPress={cycle} />
}
```

## Volume

There are two distinct volumes:

- **Player volume** — this player's own level, `0`–`1`. Use it for in-app
  controls, ducking, or fades.
- **System volume** — the device's media volume, `0`–`1`.

Player volume has no reactive hook (it only changes when you set it), so a
controlled `<Slider>` holds the value in local state — seed it from `getVolume()`
and write through to `setVolume`:

```tsx
import { getVolume, setVolume } from 'react-native-audio-browser'

function VolumeSlider() {
  const [volume, setVolumeState] = useState(() => getVolume())
  return (
    <Slider
      minimumValue={0}
      maximumValue={1}
      value={volume}
      onValueChange={(next) => {
        setVolume(next) // apply to the player
        setVolumeState(next) // keep the slider in sync
      }}
    />
  )
}
```

System volume _does_ have a hook, since the hardware buttons can change it
behind your back:

```tsx
import { useSystemVolume } from 'react-native-audio-browser'

function SystemVolumeLabel() {
  const volume = useSystemVolume() // re-renders on hardware volume keys
  return <Text>{Math.round(volume * 100)}%</Text>
}
```

`setSystemVolume` is a **no-op on iOS** — Apple provides no public API to set
the system volume (use the player volume there instead). Reading it and
`useSystemVolume` work on both platforms.

## Measuring listening time

`trackPlaybackTime` calls you back every N seconds of **actual playback** — the
clock advances only while the state is `'playing'` and freezes on
pause/buffering/stop, so paused time never inflates the count. It's built for
"still listening" analytics pings:

```ts
import { trackPlaybackTime } from 'react-native-audio-browser'

// Fire every 30 seconds of real listening. `sendHeartbeat` is your own
// analytics call. The callback also gets `sinceLast` (seconds since the
// previous fire) if you'd rather report deltas than the running total.
const cancel = trackPlaybackTime(({ total, sinceLast, track }) => {
  sendHeartbeat({ seconds: total, trackId: track?.id })
}, 30)

// later:
cancel()
```

`period` must be `>= 1` (it throws otherwise). The clock is cumulative across
the whole session and
counts _all_ play time, not per-track — `total` keeps climbing through track
changes. To measure per track, cancel and re-subscribe whenever the
[active track changes](/guide/now-playing). It's a coarse, ~1-second-resolution
signal, not a precise timer.

## API summary

| API                                             | Purpose                                                                 |
| ----------------------------------------------- | ----------------------------------------------------------------------- |
| `play()` / `pause()` / `togglePlayback()`       | Start, pause, or flip playback.                                         |
| `stop()`                                        | Stop and reset position; track stays loaded.                            |
| `reset()`                                       | Stop **and** clear the queue.                                           |
| `seekTo(seconds)` / `seekBy(offset)`            | Seek to an absolute / relative position.                                |
| `seekToLiveEdge()`                              | Jump to the live edge (no-op if not live).                              |
| `usePlayback()` / `getPlayback()`               | Full `{ state, error }`; subscribe via `onPlaybackChanged`.             |
| `retry()`                                       | Re-attempt the current item while in the `'error'` state.               |
| `usePlayingState()`                             | `{ playing, buffering }` booleans for a play/pause button.              |
| `setPlayWhenReady(bool)` / `usePlayWhenReady()` | Play/pause _intent_, independent of buffering.                          |
| `usePolledProgress(intervalMs?)`                | `{ position, duration, buffered }` via polling (ms) — works by default. |
| `useProgress()` / `getProgress()`               | Progress via events (enable `progressUpdateEventInterval` first).       |
| `getRate()` / `setRate(rate)`                   | Playback speed multiplier (`1` = normal).                               |
| `getVolume()` / `setVolume(0..1)`               | This player's volume.                                                   |
| `getSystemVolume()` / `useSystemVolume()`       | Device media volume (`setSystemVolume` is iOS no-op).                   |
| `trackPlaybackTime(cb, period)`                 | Heartbeat every `period` seconds of real playback.                      |
