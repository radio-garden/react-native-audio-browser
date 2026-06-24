# Remote Controls

**Remote controls** are the playback buttons on surfaces outside your app — the
iOS lock screen and Control Center, the Android notification, CarPlay and Android
Auto, and Bluetooth/headset buttons. By default they just work: the system
play/pause/next/seek buttons drive the player for you. This guide is for the two
times you need more — **overriding** what a button does, and **observing** when
one is pressed.

There are two families:

- [`handleRemote*`](#overriding-a-control) — **override** a control's behavior.
  A plain setter: `handleRemoteNext(cb)`, or `handleRemoteNext(undefined)` to
  clear it.
- [`onRemote*`](#listening-to-presses) — **listen** to presses without changing
  behavior (analytics, logging). An emitter:
  `onRemoteNext.addListener(cb)` returns an unsubscribe function.

Both families work on **iOS and Android**. Which buttons appear at all is
governed by [`capabilities`](/guide/configuration#capabilities)
(e.g. `jumpForward` / `jumpBackward` are off by default). This page is about
reacting to the buttons that are shown.

The snippets below import from `react-native-audio-browser` unless noted.

## They work by default

Without any wiring, each control drives the player:

| Control | Default action |
| --- | --- |
| play / pause / stop | `play()` / `pause()` / `stop()` |
| next / previous | `skipToNext()` / `skipToPrevious()` |
| seek | `seekTo(position)` |
| jump forward / backward | `seekBy(±interval)` |

So you only reach for the APIs below to *change* or *watch* that behavior.

## Overriding a control

[`handleRemote*(callback)`](/api/features/remoteControls/#handleremotenext)
**replaces** a control's default action with your own. Pass `undefined` to remove
your override (restoring the default); the callback itself does nothing but what
you write — if you still want the normal action, call it yourself. Each control
has a **single** override: calling `handleRemote*` again replaces the previous one
(unlike `onRemote*`, which supports multiple listeners).

The canonical use is gating a control — e.g. a radio product with a skip
allowance — paired with a [now-playing flash](/guide/now-playing#the-flash-transient-top-priority)
for feedback, since external surfaces have no toast:

```ts
import {
  handleRemoteNext,
  skipToNext,
  flashNowPlaying
} from 'react-native-audio-browser'

handleRemoteNext(() => {
  if (skipsRemaining() > 0) {
    skipToNext() // do the default action yourself
  } else {
    flashNowPlaying({ artist: 'Skip limit reached' }, 3000)
  }
  // Note: onRemoteNext listeners still fire on both branches (see below).
})

// To remove the override and restore the default skip:
handleRemoteNext(undefined)
```

Overrides exist for `play`, `pause`, `next`, `previous`, `stop`, `seek`,
`jumpForward`, and `jumpBackward`. The ones carrying data receive a typed event:

```ts
import {
  handleRemoteSeek,
  handleRemoteJumpForward
} from 'react-native-audio-browser'

handleRemoteSeek(({ position }) => {
  // position is in seconds
})
handleRemoteJumpForward(({ interval }) => {
  // interval is in seconds (from forwardJumpInterval)
})
```

The jump controls only appear when `jumpForward` / `jumpBackward` are enabled in
[`capabilities`](/guide/configuration#capabilities) (off by default). The jump
distance is the separate `forwardJumpInterval` / `backwardJumpInterval` option
(default 15s) — that's the `interval` your handler receives.

## Listening to presses

[`onRemote*`](/api/features/remoteControls/#onremotenext) subscribes to a press
**without** changing behavior — for analytics or logging. `addListener` returns a
cleanup function, and you can register multiple listeners:

```ts
import { onRemoteNext } from 'react-native-audio-browser'

const unsubscribe = onRemoteNext.addListener(() => {
  logEvent('remote_next')
})

// later:
unsubscribe()
```

::: warning `onRemote*` fires even when overridden
`onRemote*` reports that the **button was pressed**, not that the default action
ran. It fires on every press — including when a `handleRemote*` override
intercepted it (and even when that override refused to act). Use it to *observe*;
use `handleRemote*` to *change behavior*.
:::

## Voice commands ("play X")

A voice command like "play jazz on Android Auto" or a Siri "play …" is **not**
delivered as a remote-control event. It's handled by the library's
[search source](/guide/search) and play funnel: you provide
`configureBrowser({ search })`, and the library resolves the query and starts
playback for you (Android routes the system `MEDIA_PLAY_FROM_SEARCH` intent
through it). See [Search](/guide/search) for wiring voice intents.

## Payloads at a glance

| Event | Payload |
| --- | --- |
| `play` / `pause` / `stop` / `next` / `previous` | _(none)_ |
| `seek` | `{ position }` — seconds |
| `jumpForward` / `jumpBackward` | `{ interval }` — seconds |

## API summary

| API | Purpose |
| --- | --- |
| `handleRemotePlay/Pause/Stop/Next/Previous(cb \| undefined)` | Override a control; `undefined` restores the default. |
| `handleRemoteSeek(cb)` / `handleRemoteJumpForward/Backward(cb)` | Override, with a typed event payload. |
| `onRemotePlay/Pause/Stop/Next/Previous.addListener(cb)` | Observe a press (fires even when overridden). |
| `onRemoteSeek` / `onRemoteJumpForward` / `onRemoteJumpBackward` `.addListener(cb)` | Observe, with payload. |

Every `onRemote*` is an emitter: `.addListener(cb)` returns an unsubscribe
function. Every `handleRemote*` is a setter: pass a callback, or `undefined` to
clear it.
