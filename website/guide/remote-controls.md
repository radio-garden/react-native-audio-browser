---
description: 'Override what the lock-screen, notification, car, and headset buttons do with handleRemote*, observe presses with the onRemote* events, and resume the last session after the app is killed.'
---

# Remote Controls

**Remote controls** are the playback buttons on surfaces outside your app — the
iOS lock screen and Control Center, the Android notification, CarPlay and Android
Auto, and Bluetooth/headset buttons.

By default they just work: the system play/pause/next/seek buttons drive the
player for you. This guide is for the two times you need more — **overriding**
what a button does, and **observing** when one is pressed.

There are two families:

- [`handleRemote*`](#overriding-a-control) — **override** a control's behavior.
  A plain setter: `handleRemoteNext(cb)`, or `handleRemoteNext(undefined)` to
  restore the default behavior. To remove a button on iOS or Android, turn off
  its capability.
- [`onRemote*`](#listening-to-presses) — **listen** to presses without changing
  behavior (analytics, logging). An emitter:
  `onRemoteNext.addListener(cb)` returns an unsubscribe function.

Both families work on **iOS and Android**. Which buttons appear at all is
governed by [`capabilities`](/guide/configuration#capabilities)
(e.g. `jumpForward` / `jumpBackward` are off by default), and on Android
[`remoteButtonLayout`](#button-layout-android) decides where they sit.

The snippets below import from `react-native-audio-browser` unless noted.

## They work by default

Without any wiring, each control drives the player:

| Control                 | Default action                      |
| ----------------------- | ----------------------------------- |
| play / pause / stop     | `play()` / `pause()` / `stop()`     |
| next / previous         | `skipToNext()` / `skipToPrevious()` |
| seek                    | `seekTo(position)`                  |
| jump forward / backward | seek forward / back (see below)     |

So you only reach for the APIs below to _change_ or _watch_ that behavior.

## Button layout (Android)

Android gives you **three positions and no more**. Play/pause always holds the
centre and can't be moved, so the two named positions sit on either side of it —
they are not adjacent:

```
  back  │  ▶ play/pause  │  forward        overflow ⋯
```

Set them with [`updateOptions`](/api/features/player/#updateoptions) (or at
launch via [`setupPlayer`](/api/features/player/#setupplayer)):

```ts
updateOptions({
  capabilities: { jumpBackward: true, jumpForward: true, favorite: true },
  android: {
    remoteButtonLayout: {
      back: 'jump-backward',
      forward: 'jump-forward',
      overflow: ['skip-to-previous', 'skip-to-next', 'favorite']
    }
  }
})
```

One layout drives every Android surface — the notification, the Android Auto
Now Playing screen, and the Android 13+ system media controls.

**A layout describes the whole arrangement.** All three fields are required and
nothing is merged with the defaults, so what you write is exactly what renders —
list every button you want, not just the ones you're adding. Use `undefined` for
an empty position and `[]` for no overflow:

```ts
// Live radio: a forward skip only, nothing to the left of play/pause
remoteButtonLayout: {
  back: undefined,
  forward: 'skip-to-next',
  overflow: []
}
```

To go back to the capability-derived defaults, omit `remoteButtonLayout`
entirely or set it to `null`. That's the only switch — there's no per-field
opt-out.

### Capabilities admit, the layout arranges

A layout can **rearrange** buttons but never **add** one. Every entry is still
gated by [`capabilities`](/guide/configuration#capabilities), so naming a
button whose capability is off does nothing at all.

This catches people out, because `jumpForward` and `jumpBackward` default to
**off**:

```ts
// ✗ Both jump buttons are silently dropped — no error, no warning.
updateOptions({
  android: {
    remoteButtonLayout: {
      back: 'jump-backward',
      forward: 'jump-forward',
      overflow: []
    }
  }
})

// ✓ Enable the capability and the layout takes effect.
updateOptions({
  capabilities: { jumpBackward: true, jumpForward: true },
  android: {
    remoteButtonLayout: {
      back: 'jump-backward',
      forward: 'jump-forward',
      overflow: []
    }
  }
})
```

The reverse also holds: placement is cosmetic. A button you leave out of the
layout still responds to a Bluetooth remote or headset as long as its
capability is enabled.

### Overflow is priority, not coordinates

`overflow` is an ordered list, and each surface renders as many buttons as it
has room for, **taking them from the front**:

| Surface                                      | Roughly how many              |
| -------------------------------------------- | ----------------------------- |
| Collapsed notification                       | 3 — back, play/pause, forward |
| Expanded notification (Android 12 and below) | up to 5, play/pause included  |
| Android 13+ media controls                   | about 5                       |
| Android Auto                                 | the head unit decides         |

Two consequences worth knowing:

- A head unit with a spare slot **may promote** the first overflow entry onto
  the main row. Seeing an "overflow" button beside play/pause in the car is
  expected, not a bug.
- A long list gets **truncated**. Put what matters most first — a `'favorite'`
  in fifth place may never be drawn on a phone.

If you want a clean three-button transport row in the car, give it fewer
buttons so there's nothing to promote.

::: tip iOS
This option is Android-only. CarPlay's Now Playing buttons are configured
separately with `ios.carPlayNowPlayingButtons` — see
[CarPlay → Now Playing Buttons](/guide/carplay#now-playing-buttons).
:::

## Overriding a control

[`handleRemote*(callback)`](/api/features/remoteControls/#handleremotenext)
**replaces** a control's default action with your own. Pass `undefined` to remove
your override (restoring the default); the callback itself does nothing but what
you write — if you still want the normal action, call it yourself.

Each control has a **single** override: calling `handleRemote*` again replaces
the previous one (unlike `onRemote*`, which supports multiple listeners).

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
  // interval is in seconds
})
```

On iOS and Android the jump controls only appear when `jumpForward` /
`jumpBackward` are enabled in [`capabilities`](/guide/configuration#capabilities)
(off by default); on web capabilities are ignored and the browser's seek
forward and back actions are always handled. On iOS and Android your handler
receives the `forwardJumpInterval` / `backwardJumpInterval` option (default
15s) as `interval`; on web it receives the offset the browser sends, or 10s
when it sends none. Without a handler, the distance of the default jump depends
on the platform:

- **iOS** seeks by the interval.
- **Android** seeks by Media3's default increments (15s forward, 5s back),
  whatever the interval. There the interval sets only the button's icon and the
  handler's `interval`. To jump by the interval on Android, override the control
  and call `seekBy(±interval)`.
- **Web** seeks by the offset the browser sends, or 10s when it sends none.

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
intercepted it (and even when that override refused to act). Use it to _observe_;
use `handleRemote*` to _change behavior_.
:::

## Resuming after the app is killed

The library saves the last session natively, with nothing to configure: the
active track (with the browse path it was tapped from), its position, and the
repeat mode, shuffle and rate.

It saves on every pause, track change and setting change, and periodically
while playing, so a kill loses at most a few seconds of position. A live stream
keeps no position, so it resumes at the live edge; a track that had ended
resumes from the start.

That session plays again when a listener asks for playback while your app isn't
running:

- **Android**: a Bluetooth or headset play button, a car head unit, or the play
  button on the system media controls, which keep your app's last session after
  it is killed.
- **iOS**: Siri — "play «App»" or a bare "play" / "resume" (see
  [Search → Voice playback](/guide/search#voice-playback-siri-google-assistant)).
  Siri is the only path that resumes a saved session on iOS.

Resuming restores the settings, then rebuilds the queue by expanding the saved
track's path through your browse configuration, as if the listener had tapped it
again, and plays from the saved position.

If the path doesn't expand, the saved track plays alone. A saved track marked
[`disabled`](/guide/track) is never resumed.

Resuming waits for your JS: call `setupPlayer()` and `configureBrowser()` at the
top level of `index.js`. Resuming gives up within seconds if they haven't run
(on iOS, see
[CarPlay → AppDelegate intent handler](/guide/carplay#_4-appdelegate-intent-handler)).
On Android 12+, battery restrictions can block the background start; see
[Battery](/guide/battery).

## Voice commands ("play X")

A voice command like "play jazz on Android Auto" or a Siri "play …" is **not**
delivered as a remote-control event.

It's handled by the library's [search source](/guide/search) and play funnel:
you provide `configureBrowser({ search })`, and the library resolves the query
and starts playback for you (Android routes the system `MEDIA_PLAY_FROM_SEARCH`
intent through it). See [Search](/guide/search) for wiring voice intents.

## Payloads at a glance

| Event                                           | Payload                  |
| ----------------------------------------------- | ------------------------ |
| `play` / `pause` / `stop` / `next` / `previous` | _(none)_                 |
| `seek`                                          | `{ position }` — seconds |
| `jumpForward` / `jumpBackward`                  | `{ interval }` — seconds |

## API summary

| API                                                                                | Purpose                                               |
| ---------------------------------------------------------------------------------- | ----------------------------------------------------- |
| `handleRemotePlay/Pause/Stop/Next/Previous(cb \| undefined)`                       | Override a control; `undefined` restores the default. |
| `handleRemoteSeek(cb)` / `handleRemoteJumpForward/Backward(cb)`                    | Override, with a typed event payload.                 |
| `onRemotePlay/Pause/Stop/Next/Previous.addListener(cb)`                            | Observe a press (fires even when overridden).         |
| `onRemoteSeek` / `onRemoteJumpForward` / `onRemoteJumpBackward` `.addListener(cb)` | Observe, with payload.                                |

Every `onRemote*` is an emitter: `.addListener(cb)` returns an unsubscribe
function. Every `handleRemote*` is a setter: pass a callback, or `undefined` to
clear it.
