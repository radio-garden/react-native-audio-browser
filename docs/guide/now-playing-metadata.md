# Now Playing Metadata

The track fields you set are rendered by the *operating system*, not the library — and each surface picks different fields, composes them differently, and ignores the rest. Apple and Google document very little of this; the table below reflects observed behavior.

## Which field shows where

| Field | iOS lock screen / Control Center | CarPlay Now Playing | Android notification | Android Auto Now Playing | Bluetooth (AVRCP) |
| --- | --- | --- | --- | --- | --- |
| `title` | Primary line | Primary line | Primary line | Primary line | Title |
| `artist` | **Secondary line (sole source — `album` is never used as a fallback)** | Second line | Secondary line | Secondary line | Artist |
| `album` | Not shown | **Third line — also the tappable album/artist button (see below)** | Not shown | Rarely shown | Album (some head units) |
| `artwork` / `artworkSource` | Shown | Shown | Shown | Shown | — |
| `live` | "LIVE" indicator replaces the time scrubber | "LIVE" indicator | — | — | — |

The time scrubber (elapsed, duration, playback rate) is not driven by track fields at all — every surface derives it from the player itself. `Track.duration` is informational metadata for your app (echoed back through now-playing events); it does not affect the scrubber.

## Gotchas worth knowing

**CarPlay's tappable line renders from `album`.** When a track has an `albumUrl` (or `resolveAlbumUrl` returns a path), the *album line* becomes tappable — rendered as a third metadata line with a chevron, navigating the browse stack. A track without an `album` has no such line, so there is nothing to render or tap even though a destination exists. See the [CarPlay guide](/guide/carplay#album-line-navigation).

**The iOS lock screen never shows `album`.** Its secondary line comes from `artist` alone. If you move information from `artist` to `album` (for example to feed the CarPlay button line), it disappears from the lock screen.

**`artist` and `album` render as adjacent lines on CarPlay.** Giving both the same string displays it twice. If both fields must carry related context (a live stream's location, say), differentiate the copy — the album line reads well as an action since it can be tappable.

**One metadata dictionary feeds every surface.** There is no per-surface metadata: whatever you publish appears (or doesn't) everywhere simultaneously, per the table above. You cannot hide a field from one surface without hiding it from all of them.

**The secondary line is dynamic.** A `nowPlayingMetadataFormatter` (see `updateOptions`) can replace `title`/`artist` at runtime — typically to show live timed metadata (the current song, from ICY headers on MP3/AAC streams or ID3 cues in HLS) on the artist line while it's available. Fields the formatter leaves `undefined` fall back to the track's own values.

**Browse lists are separate.** `subtitle` drives browse-list rows (CarPlay list detail text, Android Auto list subtitle) and is never shown on now-playing surfaces; `artist` drives now-playing and is never shown in browse lists. Neither falls back to the other.
