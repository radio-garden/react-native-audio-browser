---
description: 'Control the active track — play, pause, stop, seek, playback state, progress, rate, and volume — and how playback handles the background, interruptions, and audio focus.'
---

# Playback

This guide covers **controlling the active track** — transport (play / pause /
stop), seeking, playback state, progress, rate, and volume. It's the toolkit for
building your own player UI.
[A later section](#background-audio-interruptions-and-audio-focus) covers what
the library handles for you: background audio, interruptions, and audio focus.

Playback acts on whatever the **active track** is. _Which_ track that is — and
moving between tracks with next/previous — belongs to the
[queue](/guide/queue#moving-between-tracks);
this page is about playing the one that's already active. The system surfaces
(lock screen, notification, car) are covered in [Now Playing](/guide/now-playing).

> All functions here are synchronous. Called with no track loaded, they don't
> throw: `pause`, `stop` and the seek calls do nothing, and `setPlayWhenReady`,
> `setRate` and `setVolume` keep their value for the next track. `play()` and
> `togglePlayback()` raise the play intent as `setPlayWhenReady(true)` does, so
> the next track loaded starts playing (on web, Chrome keeps that intent and
> Firefox drops it). On iOS and Android, play, pause, stop, seek, rate and
> volume calls made before `setupPlayer` has resolved are dropped; on web they
> throw `SetupNotCalledError`. On every platform `setPlayWhenReady` is kept and
> applied at setup. Two have argument rules: `setRate` and
> `trackPlaybackTime`, both covered below.

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
stop() // stop; the track stays loaded
```

`stop` keeps the track loaded, and what `play()` does afterwards depends on the
platform. On iOS, `stop` rewinds a non-live track to the start, so `play()`
plays it from the beginning. On Android and web the position is kept, so
`play()` resumes where it stopped.

`stop` is not the same as `reset`, which stops playback **and clears the queue**:

```ts
import { reset } from 'react-native-audio-browser'

reset() // tear down: stop + empty the queue
```

|           | Position                                       | Track stays loaded | Queue   |
| --------- | ---------------------------------------------- | ------------------ | ------- |
| `pause()` | kept                                           | yes                | kept    |
| `stop()`  | reset to start on iOS; kept on Android and web | yes                | kept    |
| `reset()` | —                                              | no                 | cleared |

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
[queue](/guide/queue#moving-between-tracks), not seeking.

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

`retry()` is meant for the `'error'` state, where it re-attempts the current
item. It isn't gated on that state.

On iOS it reloads the current item in any state, even while playing. On Android
and web it reloads whenever the player is stopped or failed, without raising the
play intent, so after `stop()` it loads the track but doesn't play it. See [Errors](/guide/errors#manual-retry) for the
error shape and handling.

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
actually coming out yet (it may still be loading or buffering). Setting it does
what `play()` / `pause()` do, with two exceptions. Before `setupPlayer`
resolves, `setPlayWhenReady` is kept and applied at setup, while `play()` is
dropped on iOS and Android and throws on web.

On Android, `setPlayWhenReady(true)` doesn't restart a stopped or failed player;
`play()` or `retry()` does:

```tsx
import { setPlayWhenReady, usePlayWhenReady } from 'react-native-audio-browser'

setPlayWhenReady(true) // raise the intent to play

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

`usePlayWhenReady` is the raw intent: it stays `true` after an error.
`usePlayingState().playing` is that intent only while a
track can play, so it goes `false` in the `'error'`, `'ended'`, `'none'` and
`'stopped'` states.

Both flip the instant the user taps. Neither says whether
sound is coming out yet; `buffering` does.

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

Rate is a speed multiplier, not a transport control: it's independent of
play/pause, and setting it while paused doesn't start playback.

Pass a value
greater than zero — the platforms disagree on what a non-positive rate does
(iOS ignores it, Android throws, web stalls the audio element), so treat it as
unsupported rather than as a way to pause.

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
`useSystemVolume` work on iOS and Android. On web, `setSystemVolume` does
nothing, and `getSystemVolume` and `useSystemVolume` always return `1`.

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

`period` must be `>= 1` (it throws otherwise). On Android, subscribe after
`setupPlayer` has resolved. A subscription made earlier never fires, even once
the player is up, and neither does one that was active while the playback
service restarted. To get ticks again, cancel every subscription and subscribe
once more.

`total` counts from when you subscribed, across every track that plays: it
keeps climbing through track changes. To measure per track, cancel and
re-subscribe whenever the [active track changes](/guide/queue#the-active-track). It's a coarse,
~1-second-resolution signal, not a precise timer.

`track` is the active track as of the latest track change since the first
`trackPlaybackTime` subscription. If the first subscription starts while a track
is already playing, it is `undefined` until the next track change.

## Background audio, interruptions and audio focus

Playback carries on with your app in the background and the screen locked:

- **iOS** needs the `audio` background mode in your app's `Info.plist` (see
  [Getting Started](/guide/getting-started#installation)). The audio session
  category defaults to `'playback'`; `ios.category`, `categoryMode`,
  `categoryOptions` and `categoryPolicy` change it at setup
  ([iOS options](/guide/configuration#ios-options-ios)).
- **Android** plays from the library's foreground media service, which its
  merged manifest declares. What happens when the app is swiped away is set by
  `android.appKilledPlaybackBehavior`
  ([Android options](/guide/configuration#android-options-android)).

On iOS, the library activates the audio session when playback starts (on
`play()`, not at setup), so launching your app doesn't stop other apps' audio. It releases the
session about 3 seconds after playback stops, so their audio can come back.

**Interruptions** (a phone call, an alarm, another app starting audio) are
handled natively, with nothing to wire up:

- On **iOS**, an interruption pauses playback. When it ends, playback resumes
  if iOS says it should and the player was playing when it began; otherwise it
  stays paused.
- On **Android**, the player holds audio focus. It pauses while another app
  holds focus briefly and resumes when focus returns. It pauses when another
  app takes focus for good. It lowers its volume under a short sound such as a
  notification, or pauses instead with `android.audioContentType: 'speech'`.

When the output disappears (headphones unplugged, a Bluetooth speaker switched
off), playback pauses; see
[Audio Output](/guide/audio-output#you-may-not-need-this-for-unplug-handling).
On Android, `android.handleAudioBecomingNoisy: false` turns that off.

## API summary

| API                                             | Purpose                                                                 |
| ----------------------------------------------- | ----------------------------------------------------------------------- |
| `play()` / `pause()` / `togglePlayback()`       | Start, pause, or flip playback.                                         |
| `stop()`                                        | Stop; track stays loaded (position resets to the start on iOS only).    |
| `reset()`                                       | Stop **and** clear the queue.                                           |
| `seekTo(seconds)` / `seekBy(offset)`            | Seek to an absolute / relative position.                                |
| `seekToLiveEdge()`                              | Jump to the live edge (no-op if not live).                              |
| `usePlayback()` / `getPlayback()`               | Full `{ state, error }`; subscribe via `onPlaybackChanged`.             |
| `retry()`                                       | Re-attempt the current item; meant for the `'error'` state.             |
| `usePlayingState()` / `getPlayingState()`       | `{ playing, buffering }` booleans for a play/pause button.              |
| `onPlayingState`                                | Subscribe to `{ playing, buffering }` changes.                          |
| `setPlayWhenReady(bool)` / `usePlayWhenReady()` | Play/pause _intent_, independent of buffering.                          |
| `getPlayWhenReady()` / `onPlayWhenReadyChanged` | Read or subscribe to the play/pause intent.                             |
| `usePolledProgress(intervalMs?)`                | `{ position, duration, buffered }` via polling (ms) — works by default. |
| `useProgress()` / `onProgressUpdated`           | Progress via events (enable `progressUpdateEventInterval` first).       |
| `getProgress()`                                 | One-off progress snapshot; always works.                                |
| `getRate()` / `setRate(rate)`                   | Playback speed multiplier (`1` = normal).                               |
| `getVolume()` / `setVolume(0..1)`               | This player's volume.                                                   |
| `getSystemVolume()` / `useSystemVolume()`       | Device media volume (`setSystemVolume` does nothing on iOS or web).     |
| `onSystemVolumeChanged`                         | Subscribe to device media volume changes.                               |
| `trackPlaybackTime(cb, period)`                 | Heartbeat every `period` seconds of real playback.                      |
