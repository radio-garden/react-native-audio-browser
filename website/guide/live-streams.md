---
description: "The map for live audio: stream formats, missing durations, dropped connections, expiring URLs, song titles carried in-band, and Android's periodic position updates."
---

# Live Streams

Live audio is what most of this player's edge cases are about: streams that
have no duration, drop in tunnels, expire their URLs, and carry their song
titles in-band.

The pieces live in their own guides — this page is the map,
with just enough of each to know which link you need.

## Stream formats

Point [`src`](/api/types/browser-nodes/#src) at the stream URL — there is
no format field; each platform's player detects the container:

- **HLS** (`.m3u8`) — iOS, Android and web. iOS and Android read in-band
  ID3 timed metadata.
- **ICY / Icecast / Shoutcast** — plain HTTP radio, iOS, Android and web. On
  iOS and Android the ICY `StreamTitle` arrives as timed metadata, filling
  only `title`.
- **Progressive HTTP** — ordinary MP3/AAC/… files and endless streams, iOS,
  Android and web.
- **DASH** — Android (bundled Media3 module) and web; iOS's AVPlayer doesn't
  support it. **SmoothStreaming** is Android only.

Timed metadata of either kind reaches the lock screen only once your
[now-playing formatter](/guide/now-playing#the-formatter-derived-continuous)
applies it, never automatically (see
[Metadata](/guide/metadata#timed-metadata-the-live-song)).

Mark the track itself with [`live: true`](/api/types/browser-nodes/#live):
it arms [`seekToLiveEdge()`](#the-live-edge) on every platform, and gives
now-playing non-scrubbing controls on iOS and Android. On Android a cast
receiver also loads it as a live stream. On iOS it also shows the live
indicator, turns an end of stream or a stall into a reconnect at the live
edge, and skips saving a position. Declare it on every live track.

To hear it, put the track in the queue (or serve it from a
[browse tree](/guide/basic-usage)) after
[`setupPlayer()`](/guide/basic-usage#set-up-the-player) has run:

```ts
import { play, setQueue } from 'react-native-audio-browser'

setQueue([
  {
    src: 'https://stream.example.com/main.mp3',
    title: 'Beacon FM',
    live: true
  }
])
play()
```

## Dropouts: stalls and retry

A connection drop mid-stream surfaces one of two ways, with different owners:

- **A stall** (how most iOS drops surface) — the player holds `'buffering'`
  with play intent, and re-establishes the stream by itself when
  connectivity returns (see
  [Network → You may not need this](/guide/network#you-may-not-need-this)).
  While stalled offline, the [now-playing formatter](/guide/now-playing#the-formatter-derived-continuous)
  receives `stalled: 'offline'` (vs `'buffering'` when online) so the lock
  screen can say why.
- **A playback error** (how most Android drops surface) — recovery belongs
  to [automatic retry](/guide/errors#automatic-retry)
  (`setupPlayer({ retry })`, off by default). Which of
  [two duration budgets](/guide/errors#two-duration-budgets) applies depends
  on whether this load has ever produced audio:
  - **First connect**, for a stream that never played. Only online time
    counts, so a station tapped in a tunnel still gets its chance when the
    network returns.
  - **Recovery**, once playback proved the stream works. This one is
    wall-clock time and includes offline time, so playback never resumes by
    surprise after a long outage.

  Once the budget runs out, the state is `'error'`, and
  [`retry()`](/guide/errors#manual-retry) and your error UI take over.

Android's reconnect-on-restore only arms through the retry machinery, so
the platform whose drops become errors is also the one that won't heal
without it. **If surviving tunnels matters to you, enable `retry`.**

The web implementation has no automatic retry at all, and no timed metadata,
neither ICY nor HLS ID3. Every web error is terminal.

## Fresh URLs on reconnect

If your stream URLs are signed or short-lived, build them in
[`media.resolve`](/guide/browser#media-and-artwork) — the per-track layer of
the stream request. When a reconnect re-runs it depends on the platform:

- **iOS** re-runs it on every reload: automatic retry, network restore,
  play-from-`'error'` and a live-edge reconnect. A URL that expired during
  the outage is re-signed instead of re-failing.
- **Android** reuses the URL it resolved, and re-runs `media.resolve` only
  after an attempt to open that URL has failed. A URL that expired
  mid-stream therefore costs one failed attempt.

  With `retry` on, a status that retrying can't fix (a 401 or 403, say; see
  [which failures qualify](/guide/errors#automatic-retry)) puts playback in
  `'error'`, and the next [`retry()`](/guide/errors#manual-retry) or play
  command re-resolves. With `retry` off, ExoPlayer's own few retries cover any
  status, and the first of them re-resolves.

- **Web** re-runs it on every reload: play-from-`'error'` or after a stop,
  `retry()` and a live-edge reconnect. Web has no automatic retry.

```ts
import { configureBrowser } from 'react-native-audio-browser'

// Merge `media` into your one configureBrowser call — it replaces the
// whole config, so a media-only call would drop your tabs and routes.
configureBrowser({
  media: {
    resolve: async (track) => ({
      path: `/stream/${track.id}`,
      // sign() is yours — however your backend mints stream tokens
      query: { token: await sign(track.id) }
    })
  }
})
```

A relative `path` is joined to your configured `baseUrl` — see
[Shaping requests](/guide/browser#shaping-requests) for the layering.

## The live edge

[`seekToLiveEdge()`](/api/features/playback/#seektoliveedge) jumps a live
track back to the newest available audio — a no-op unless the current track
declares `live: true`.

On a stream with a seekable live window (HLS with a sliding window) it seeks
to the window's edge; on a non-seekable one (typical ICY radio) it
reconnects instead — a fresh connection _is_ the live edge.

On iOS and web the reconnect re-runs `media.resolve`. On Android it reuses the
resolved URL, so an expired one re-resolves only after that first attempt
fails (see [Fresh URLs on reconnect](#fresh-urls-on-reconnect)).
General seeking is in [Playback](/guide/playback).

## Pausing a live stream

On iOS and Android, how a live track pauses depends on whether it can be
rewound.

A stream that can't be rewound holds no connection while paused or stopped.
That covers progressive (ICY/Icecast) radio, and live HLS whose seekable
window is under 30 seconds. Left open, a paused player goes on downloading
it (on macOS for as long as it stays paused), and a resume plays what it
buffered, behind the stream. So pause and stop close the connection. The
now-playing surfaces go on showing the track, paused, and play reconnects at
the live edge.

A stream with a seekable window of 30 seconds or more (HLS with a DVR window)
pauses as an on-demand track does. It keeps its position, and play resumes
from there. [`seekToLiveEdge()`](/api/features/playback/#seektoliveedge)
while paused moves it to the window's end. On iOS a paused one keeps what it
has buffered but loads no more than 10 seconds ahead, where it would otherwise
go on loading toward the live edge; on Android the buffer limit (`maxBuffer`)
bounds it. A shorter window doesn't count: a short pause would carry the
position out of it.

A live track loaded without play intent (`load` or `setQueue` without
`playWhenReady`) opens no connection. It stays `loading` for a second, so a
`play()` that follows connects at once, with no `paused` in between. With no
play by then it reports `paused`. On iOS this holds for a seekable window too,
since a window isn't known before the stream connects. On Android a live
track loaded or skipped to while paused still connects and buffers.

## Periodic position updates (Android)

While the player plays or loads, Media3's media session sends External
Surfaces the playback state again every 3 seconds: the notification, the lock
screen, Android Auto, Bluetooth, and Media3 controllers in other apps (a
watch, say). The update carries the current and buffered position, so a
surface that extrapolates the position corrects any drift.

A live track has no progress bar, so a drifting position never shows, and the
update only refreshes the state's timestamp. Android 16's Bluetooth service
logs each one as `tried to update with no new data`.

If your app plays only live streams, turn the update off with a literal
`false` in your app's manifest:

```xml
<!-- android/app/src/main/AndroidManifest.xml, inside <application> -->
<meta-data
  android:name="com.audiobrowser.PERIODIC_POSITION_UPDATES"
  android:value="false" />
```

Every real change still reaches the surfaces at once: state, play and pause,
seeks, speed, Now Playing and the queue. Surfaces extrapolate an on-demand
position from the last state, so their progress bars keep moving. What stops
is the correction: a position that drifts with no state change (a Sonos
speaker that slips while it still reports playing, say) stays wrong on the
surfaces until the next change.

Leave it on, the default, if your app plays on-demand audio. The flag is
Android-only: iOS and web have no such update.

## API summary

| API                                                               | Purpose                                                                 |
| ----------------------------------------------------------------- | ----------------------------------------------------------------------- |
| [`Track.live`](/api/types/browser-nodes/#live)                    | Declare a live track: iOS live indicator, arms the live edge.           |
| [`seekToLiveEdge()`](/api/features/playback/#seektoliveedge)      | Jump to the newest audio (no-op for non-live tracks).                   |
| [`setupPlayer({ retry })`](/guide/errors#automatic-retry)         | Automatic retry with first-connect and recovery budgets.                |
| [`retry()`](/guide/errors#manual-retry)                           | Restart from `'error'` with fresh budgets.                              |
| [`media.resolve`](/guide/browser#media-and-artwork)               | Build the stream request — signed / short-lived URLs.                   |
| [`useOnline()`](/guide/network)                                   | Connectivity for your own UI; playback reacts on its own.               |
| [`PERIODIC_POSITION_UPDATES`](#periodic-position-updates-android) | Android manifest flag: stop the 3 s position update for live-only apps. |
