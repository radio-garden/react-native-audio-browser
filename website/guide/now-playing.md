---
description: 'What the system surfaces show: which metadata field lands where, the four layers that decide its value at any moment, and reading the result back in your own UI.'
---

# Now Playing

The now-playing surface is everything that displays the current track on the _system's_ surfaces: the iOS lock screen and Control Center, the Android notification, CarPlay and Android Auto's Now Playing screens, and Bluetooth head units.

The library publishes one metadata dictionary and the _operating system_ renders it. Each surface picks different fields, composes them differently, and ignores the rest. Your own app can read the same metadata: see [Reading now-playing in your own UI](#reading-now-playing-in-your-own-ui).

This guide covers: **which fields land where** (the static matrix), **the four layers that decide what those fields contain at any moment** (the dynamic model), and **reading the result back** in your own UI.

## Which field shows where

Apple and Google document very little of this; the table below reflects observed behavior.

| Field     | iOS lock screen / Control Center                                       | CarPlay Now Playing                                                | Android notification | Android Auto Now Playing | Bluetooth (AVRCP)       |
| --------- | ---------------------------------------------------------------------- | ------------------------------------------------------------------ | -------------------- | ------------------------ | ----------------------- |
| `title`   | Primary line                                                           | Primary line                                                       | Primary line         | Primary line             | Title                   |
| `artist`  | **Secondary line (sole source — `album` is never used as a fallback)** | Second line                                                        | Secondary line       | Secondary line           | Artist                  |
| `album`   | Not shown                                                              | **Third line — also the tappable album/artist button (see below)** | Not shown            | Rarely shown             | Album (some head units) |
| `artwork` | Shown                                                                  | Shown                                                              | Shown                | Shown                    | —                       |
| `live`    | "LIVE" indicator replaces the time scrubber                            | "LIVE" indicator                                                   | —                    | —                        | —                       |

The time scrubber (elapsed, duration, playback rate) is not driven by track fields at all — every surface derives it from the player itself. `Track.duration` is informational metadata for your app (echoed back through now-playing events); it does not affect the scrubber.

`artwork` is the URL you _set_ on a track. On iOS and Android, surfaces show the resolved image, not the raw URL. On web, the Media Session gets the raw URL. The layers below cannot change it: see [Now-playing artwork](#now-playing-artwork).

## The four metadata layers

What actually renders is decided by a fixed priority order. Each layer only overrides the fields it sets. A field it leaves `undefined` falls through to the layer below, except under a flash (see [the flash](#the-flash-transient-top-priority)):

| Priority     | Layer            | API                                    | Lifetime                                                                                  |
| ------------ | ---------------- | -------------------------------------- | ----------------------------------------------------------------------------------------- |
| 1 (highest)  | **Flash**        | `flashNowPlaying(update, durationMs)`  | `durationMs`, on a native timer (web: see [the flash](#the-flash-transient-top-priority)) |
| 2            | **Formatter**    | `setupPlayer({ nowPlaying })`          | as long as it's configured                                                                |
| 3            | **Override**     | `updateNowPlaying(update)`             | until cleared or the track changes                                                        |
| 4 (baseline) | **Track fields** | `Track.title` / `artist` / `album` / … | the track's time as the active track                                                      |

### Track fields: the baseline

With nothing else configured, the active track's own fields are published as-is. For on-demand content this is usually all you need.

### The formatter: derived, continuous

The [`nowPlaying`](/api/features/player/#formatnowplayingcallback) formatter hands you the now-playing text lines outright. The library calls it again whenever the lines could change:

- on a track change
- on every timed-metadata update
- on every playback transition: play / pause, a stall starting or recovering, an error
- on connectivity changes

It's the right layer for anything _derived from playback state_: the live song from ICY/ID3 metadata, a "Reconnecting…" line during a stall, an error message:

```ts
setupPlayer({
  nowPlaying: ({ timedMetadata, playWhenReady, stalled, error }) => {
    // `yourErrorLine` is your own kind→copy mapping. Branch on
    // `error.kind`, never `error.message`, which is developer English
    // and never localized.
    // This line is read by listeners, on the lock screen and in the car.
    // See [Playback errors](/guide/errors#playback-errors).
    if (error) return { artist: yourErrorLine(error.kind) }
    if (stalled) {
      return {
        artist: stalled === 'offline' ? 'No connection' : 'Reconnecting…'
      }
    }
    // The live song, only while actually playing — a paused stream's last
    // song is stale, so fall back to the track default (return undefined).
    if (!playWhenReady || !timedMetadata?.title) return
    return { artist: timedMetadata.title }
  }
})
```

The callback receives a single [`FormatNowPlayingParams`](/api/features/player/#formatnowplayingparams) object:

| Field           | Type             | Meaning                                                                                                                                                                       |
| --------------- | ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `track`         | `Track`          | The currently playing track.                                                                                                                                                  |
| `timedMetadata` | `TimedMetadata?` | The ICY / ID3 "now playing song", if any. On iOS, also a file's own tags when the file has no chapters.                                                                       |
| `playWhenReady` | `boolean`        | Play/pause intent — stays `true` through buffers, so the song line won't flicker.                                                                                             |
| `stalled`       | `StallReason?`   | Truthy only during a mid-stream stall: `'buffering'` (rebuffering while online) or `'offline'` (no connectivity). Use `if (stalled)`, compare `=== 'offline'` for the reason. |
| `error`         | `PlaybackError?` | The current playback error, if playback failed. Switch on `error.kind` to pick your own localized line: see [Playback errors](/guide/errors#playback-errors).                 |

While [automatic retry](/guide/errors#automatic-retry) is still working, `error` carries the advisory error (`error.retrying === true`). The formatter runs again when that error arrives, changes and clears. So you can show provisional copy ("Retrying…") that becomes the final line when the player gives up.

It returns a [`NowPlayingUpdate`](/api/features/metadata/#nowplayingupdate) (`{ title?, artist?, album? }`) or `undefined`. The callback is synchronous and should stay cheap: a pure formatting function, no I/O.

Each returned field falls back **independently** to the track's value when omitted. Returning `undefined` (or `{}`) uses the track default entirely. Identical results across a rapid burst of transitions are de-duplicated natively, so they won't flicker the surface.

Timed metadata is never auto-applied: the library surfaces ICY (Shoutcast/Icecast) and in-band ID3 (HLS) frames to your code, and the formatter is where you decide what reaches the now-playing line. (To consume those frames outside the formatter, subscribe to [`onTimedMetadata`](/api/features/metadata/#ontimedmetadata).)

### The override: imperative, sticky

[`updateNowPlaying({ artist: '…' })`](/api/features/nowPlaying/#updatenowplaying) pins fields until you pass `null` or the track changes. It's the layer to reach for when you _don't_ configure a formatter.

**If a formatter is configured, it outranks the override.** The formatter's result is applied on top, falling back per-field to `override ?? track`. With a formatter in place, feed it state instead of calling `updateNowPlaying` around it. Prefer the formatter for anything derived from playback state.

### The flash: transient, top priority

[`flashNowPlaying(update, durationMs)`](/api/features/nowPlaying/#flashnowplaying) is the toast of the now-playing world: it briefly replaces the fields it sets, then reverts to whatever the lower layers say.

External surfaces have no notification primitive, so a transient metadata swap is the only way to give feedback there. The canonical use is answering a refused remote command:

```ts
// A radio product with an hourly skip allowance:
handleRemoteNext(() => {
  if (skipsRemaining() > 0) {
    skipToNext()
  } else {
    flashNowPlaying({ artist: 'Skip limit reached' }, 3000)
  }
})
```

Three properties make it a dedicated layer rather than sugar over `updateNowPlaying`:

- **It outranks the formatter.** While a flash is active the formatter pass is skipped, so a live station's next metadata tick can't overwrite the message mid-window. Fields the flash leaves unset come from the override or the track, not from the formatter. Set every line you want to keep.
- **The revert runs on a native timer.** A JS `setTimeout` pauses with a backgrounded host on Android — and remote commands from the lock screen arrive exactly when the host is backgrounded. The native timer fires regardless.
- **A track change clears it early.** A flash is feedback about a moment; it never carries over to a new track.

Repeated calls restart the window. [`clearNowPlayingFlash()`](/api/features/nowPlaying/#clearnowplayingflash) cancels one imperatively — for example when the condition the flash complained about resolves mid-window.

::: warning Web and `nowPlaying: false`
The three properties above hold on iOS and Android. On web, a flash is an `updateNowPlaying` override with a JS timer. It replaces any override you set, and the revert (or `clearNowPlayingFlash()`) clears the override instead of restoring it.

A track change doesn't cancel the timer, so a pending revert also clears an override you set for the next track.

With `nowPlaying: false`, Android ignores the flash, while iOS still shows it.
:::

## Reading now-playing in your own UI

The metadata you publish is also what your own app should render — a mini-player, a full-screen "now playing" view — so the in-app UI matches the lock screen exactly, formatter and all. Use the [`useNowPlaying`](/api/features/nowPlaying/#usenowplaying) hook:

```tsx
import { Text } from 'react-native'
import { useNowPlaying } from 'react-native-audio-browser'

function MiniPlayer() {
  const nowPlaying = useNowPlaying()
  if (!nowPlaying) return null
  return (
    <Text>
      {nowPlaying.title} — {nowPlaying.artist}
    </Text>
  )
}
```

`useNowPlaying` returns the resolved [`NowPlayingMetadata`](/api/features/metadata/#nowplayingmetadata): the same dictionary the system surfaces see, _after_ the four layers are applied. The exception is `nowPlaying: false` on iOS and Android: the hook still reports an `updateNowPlaying` override the surface ignores (on Android, a flash too). It returns `undefined` when there is no active track. It carries `title` / `artist` / `album` / `artwork`.

It also carries `elapsedTime`, but only on iOS, and it is not a live position. For the scrubber position, use [`useProgress`](/guide/playback#progress).

Outside React, [`getNowPlaying()`](/api/features/nowPlaying/#getnowplaying) reads a snapshot. [`onNowPlayingChanged`](/api/features/nowPlaying/#onnowplayingchanged) fires whenever the published lines change: a new track, a formatter result, an override, or a flash starting or ending.

To render artwork, don't use this `artwork` field: it is a plain URL string, and on Android and web it is the track's `artwork` before your artwork config resolves it. Use the active track's `artworkSource` (`<Image source={track.artworkSource} />`). See [Track](/guide/track).

## Gotchas worth knowing

**CarPlay's tappable line renders from `album`.** When a track has an `albumPath` (or `resolveAlbumPath` returns a path), the _album line_ becomes tappable. It renders as a third metadata line with a chevron and navigates the browse stack.

A track without an `album` has no such line, so there is nothing to tap even though a destination exists. See the [CarPlay guide](/guide/carplay#album-line-navigation).

**The iOS lock screen never shows `album`.** Its secondary line comes from `artist` alone. If you move information from `artist` to `album` (for example to feed the CarPlay button line), it disappears from the lock screen.

**`artist` and `album` render as adjacent lines on CarPlay.** Giving both the same string displays it twice. If both fields must carry related context (a live stream's location, say), differentiate the copy — the album line reads well as an action since it can be tappable.

**One metadata dictionary feeds every surface.** There is no per-surface metadata: whatever you publish appears (or doesn't) everywhere simultaneously, per the table above. You cannot hide a field from one surface without hiding it from all of them.

**A formatter quietly disables `updateNowPlaying` for the fields it returns.** See [the override](#the-override-imperative-sticky): the formatter's result wins per-field. If a line you set imperatively keeps reverting, a formatter is overwriting it; either move that logic into the formatter or use `flashNowPlaying` if it was meant to be transient.

**Browse lists are separate.** `subtitle` drives browse-list rows (CarPlay list detail text, Android Auto list subtitle) and is never shown on now-playing surfaces; `artist` drives now-playing and is never shown in browse lists. Neither falls back to the other.

## Now-playing artwork

Artwork is the one surface field the text layers (formatter / override / flash) can't touch: they carry only `title` / `artist` / `album`. On iOS and Android, surfaces show the active track's resolved artwork, `artworkSource`. That is the track's `artwork` after your [artwork configuration](/guide/browser#media-and-artwork) transforms it. On web, the Media Session gets the track's raw `artwork` URL (the `dark` one of a pair), which neither artwork config touches.

By default that's the same image as the track's browse-list thumbnail. To resolve it differently, set the `nowPlayingArtwork` config kind. It builds the now-playing image from its own request (e.g. `{ path: '/artwork/{id}' }`), for example a larger, lock-screen-quality image that does not bloat list thumbnails:

```ts
configureBrowser({
  nowPlayingArtwork: { path: '/artwork/{id}' }
  // ...tabs, routes
})
```

See [`BrowserConfiguration.nowPlayingArtwork`](/api/types/browser/#browserconfiguration).

## API summary

| API                                       | Purpose                                                     |
| ----------------------------------------- | ----------------------------------------------------------- |
| `setupPlayer({ nowPlaying })`             | Configure the formatter: derived lines from playback state. |
| `updateNowPlaying(update \| null)`        | Imperatively override fields; `null` clears.                |
| `flashNowPlaying(update, durationMs)`     | Transient, top-priority swap (refused-command feedback).    |
| `clearNowPlayingFlash()`                  | Cancel an active flash early.                               |
| `useNowPlaying()` / `getNowPlaying()`     | Read the resolved metadata in your own UI.                  |
| `onNowPlayingChanged`                     | Subscribe to metadata changes outside React.                |
| `onTimedMetadata`                         | Subscribe to ICY / ID3 stream metadata directly.            |
| `configureBrowser({ nowPlayingArtwork })` | Resolve now-playing artwork from its own request.           |
