# Now Playing

The now-playing surface is everything that displays the current track *outside your app's own UI*: the iOS lock screen and Control Center, the Android notification, CarPlay and Android Auto's Now Playing screens, and Bluetooth head units. The library publishes one metadata dictionary and the *operating system* renders it — each surface picks different fields, composes them differently, and ignores the rest.

This guide covers both halves of working with it: **which fields land where** (the static matrix), and **the four layers that decide what those fields contain at any moment** (the dynamic model).

## Which field shows where

Apple and Google document very little of this; the table below reflects observed behavior.

| Field | iOS lock screen / Control Center | CarPlay Now Playing | Android notification | Android Auto Now Playing | Bluetooth (AVRCP) |
| --- | --- | --- | --- | --- | --- |
| `title` | Primary line | Primary line | Primary line | Primary line | Title |
| `artist` | **Secondary line (sole source — `album` is never used as a fallback)** | Second line | Secondary line | Secondary line | Artist |
| `album` | Not shown | **Third line — also the tappable album/artist button (see below)** | Not shown | Rarely shown | Album (some head units) |
| `artwork` / `artworkSource` | Shown | Shown | Shown | Shown | — |
| `live` | "LIVE" indicator replaces the time scrubber | "LIVE" indicator | — | — | — |

The time scrubber (elapsed, duration, playback rate) is not driven by track fields at all — every surface derives it from the player itself. `Track.duration` is informational metadata for your app (echoed back through now-playing events); it does not affect the scrubber.

## The four metadata layers

What actually renders is decided by a fixed priority order. Each layer only overrides the fields it sets — anything it leaves `undefined` falls through to the layer below:

| Priority | Layer | API | Lifetime |
| --- | --- | --- | --- |
| 1 (highest) | **Flash** | `flashNowPlaying(update, durationMs)` | `durationMs`, on a native timer |
| 2 | **Formatter** | `setupPlayer({ autoUpdateNowPlaying })` | as long as it's configured |
| 3 | **Override** | `updateNowPlaying(update)` | until cleared or the track changes |
| 4 (baseline) | **Track fields** | `Track.title` / `artist` / `album` / … | the track's time as the active track |

### Track fields — the baseline

With nothing else configured, the active track's own fields are published as-is. For on-demand content this is usually all you need.

### The formatter — derived, continuous

`autoUpdateNowPlaying` hands you the now-playing text lines outright, re-invoked whenever they could change: on track change, on every timed-metadata update, and on every playback-state change. It's the right layer for anything *derived from playback state* — the live song from ICY/ID3 metadata, a "Reconnecting…" line during a stall, an error message:

```ts
setupPlayer({
  autoUpdateNowPlaying: ({ timedMetadata, playWhenReady, stalled, error }) => {
    if (error) return { artist: error.message }
    if (stalled) return { artist: 'Reconnecting…' }
    // The live song, only while actually playing — a paused stream's last
    // song is stale, so fall back to the track default (return undefined).
    if (!playWhenReady || !timedMetadata?.title) return
    return { artist: timedMetadata.title }
  }
})
```

The callback is synchronous and should stay cheap — it's a pure formatting function. Returning `undefined` (entirely, or per field) falls back to the layers below.

Timed metadata is never auto-applied: the library surfaces ICY (Shoutcast/Icecast) and in-band ID3 (HLS) frames to your code, and the formatter is where you decide what reaches the now-playing line.

### The override — imperative, sticky

`updateNowPlaying({ artist: '…' })` pins fields until you pass `null` or the track changes. It predates the formatter and remains useful when you *don't* configure one.

**If a formatter is configured, it outranks the override** — the formatter's result is applied on top, falling back per-field to `override ?? track`. Mixing the two is rarely what you want: with a formatter in place, feed it state instead of calling `updateNowPlaying` around it.

### The flash — transient, top priority

`flashNowPlaying(update, durationMs)` is the toast of the now-playing world: it briefly replaces the fields it sets, then reverts to whatever the lower layers say. External surfaces have no notification primitive, so a transient metadata swap is the only way to give feedback there — the canonical use is answering a refused remote command:

```ts
handleRemoteNext(() => {
  flashNowPlaying({ artist: 'Skipping requires Premium' }, 3000)
})
```

Three properties make it a dedicated layer rather than sugar over `updateNowPlaying`:

- **It outranks the formatter.** While a flash is active the formatter pass is skipped entirely, so a live station's next metadata tick can't overwrite the message mid-window.
- **The revert runs on a native timer.** A JS `setTimeout` pauses with a backgrounded host on Android — and remote commands from the lock screen arrive exactly when the host is backgrounded. The native timer fires regardless.
- **A track change clears it early.** A flash is feedback about a moment; it never carries over to a new track.

Repeated calls restart the window. `clearNowPlayingFlash()` cancels one imperatively — for example when the condition the flash complained about resolves mid-window.

## Gotchas worth knowing

**CarPlay's tappable line renders from `album`.** When a track has an `albumUrl` (or `resolveAlbumUrl` returns a path), the *album line* becomes tappable — rendered as a third metadata line with a chevron, navigating the browse stack. A track without an `album` has no such line, so there is nothing to render or tap even though a destination exists. See the [CarPlay guide](/guide/carplay#album-line-navigation).

**The iOS lock screen never shows `album`.** Its secondary line comes from `artist` alone. If you move information from `artist` to `album` (for example to feed the CarPlay button line), it disappears from the lock screen.

**`artist` and `album` render as adjacent lines on CarPlay.** Giving both the same string displays it twice. If both fields must carry related context (a live stream's location, say), differentiate the copy — the album line reads well as an action since it can be tappable.

**One metadata dictionary feeds every surface.** There is no per-surface metadata: whatever you publish appears (or doesn't) everywhere simultaneously, per the table above. You cannot hide a field from one surface without hiding it from all of them.

**A formatter quietly disables `updateNowPlaying` for the fields it returns.** See [the override](#the-override--imperative-sticky) — the formatter's result wins per-field. If a line you set imperatively keeps reverting, a formatter is overwriting it; either move that logic into the formatter or use `flashNowPlaying` if it was meant to be transient.

**Browse lists are separate.** `subtitle` drives browse-list rows (CarPlay list detail text, Android Auto list subtitle) and is never shown on now-playing surfaces; `artist` drives now-playing and is never shown in browse lists. Neither falls back to the other.

**Now-playing artwork can resolve differently from list artwork.** The `nowPlayingArtwork` config kind builds the now-playing image from its own request (e.g. `{ path: '/artwork/{id}' }`) without putting thumbnails in browse lists — see `BrowserConfiguration.nowPlayingArtwork`.
