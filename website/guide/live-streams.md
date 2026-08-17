---
description: 'The map for live audio: stream formats, missing durations, dropped connections, expiring URLs, and song titles carried in-band.'
---

# Live Streams

Live audio is what most of this player's edge cases are about: streams that
have no duration, drop in tunnels, expire their URLs, and carry their song
titles in-band. The pieces live in their own guides — this page is the map,
with just enough of each to know which link you need.

## Stream formats

Point [`src`](/api/types/browser-nodes/#track) at the stream URL — there is
no format field; each platform's player detects the container:

- **HLS** (`.m3u8`) — both platforms. In-band ID3 timed metadata is read —
  see [Metadata](/guide/metadata#timed-metadata-the-live-song).
- **ICY / Icecast / Shoutcast** — plain HTTP radio, both platforms. The ICY
  `StreamTitle` arrives as timed metadata, filling only `title` — it reaches
  the lock screen once your
  [now-playing formatter](/guide/now-playing#the-formatter-derived-continuous)
  applies it, never automatically (see
  [Metadata](/guide/metadata#timed-metadata-the-live-song)).
- **Progressive HTTP** — ordinary MP3/AAC/… files and endless streams, both
  platforms.
- **DASH / SmoothStreaming** — Android only (bundled Media3 modules); iOS's
  AVPlayer doesn't support them.

Mark the track itself with [`live: true`](/api/types/browser-nodes/#track):
it shows the live indicator on iOS now-playing and arms
[`seekToLiveEdge()`](#the-live-edge) — declare it on every live track.
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
  connectivity returns. No wiring needed — see
  [Network → You may not need this](/guide/network#you-may-not-need-this).
  While stalled offline, the [now-playing formatter](/guide/now-playing#the-formatter-derived-continuous)
  receives `stalled: 'offline'` (vs `'buffering'` when online) so the lock
  screen can say why.
- **A playback error** (how most Android drops surface) — recovery belongs
  to [automatic retry](/guide/errors#automatic-retry)
  (`setupPlayer({ retry })`, off by default). Two duration budgets, chosen
  by whether this load has ever produced audio: **12 s** for a stream that
  never played (counting only online time — a station tapped in a tunnel
  still gets its chance when the network returns), **2 min** once playback
  proved the stream works. The 2 min budget is a wall clock that _includes_
  offline time, deliberately — see
  [the budgets](/guide/errors#two-duration-budgets). Budgets exhausted →
  state `'error'`, where [`retry()`](/guide/errors#manual-retry) and your
  error UI take over.

Android's reconnect-on-restore only arms through the retry machinery, so
the platform whose drops become errors is also the one that won't heal
without it. **If surviving tunnels matters to you, enable `retry`.** (The
web implementation has no automatic retry at all, and no ICY metadata —
every web error is terminal.)

## Fresh URLs on every reconnect

If your stream URLs are signed or short-lived, build them in
[`media.resolve`](/guide/browser#media-and-artwork) — the per-track layer of
the stream request. Every recovery path re-runs it rather than replaying the
cached URL: automatic retry, network restore, play-from-`'error'`. A URL
that expired during the outage is re-signed instead of re-failing.

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
declares `live: true`. On a stream with a seekable live window (HLS with a
sliding window) it seeks to the window's edge; on a non-seekable one
(typical ICY radio) it reconnects instead — a fresh connection _is_ the
live edge, and an expired URL re-resolves through `media.resolve`. General
seeking is in [Playback](/guide/playback).

## API summary

| API                                                          | Purpose                                                           |
| ------------------------------------------------------------ | ----------------------------------------------------------------- |
| [`Track.live`](/api/types/browser-nodes/#track)              | Declare a live track: iOS live indicator, arms the live edge.     |
| [`seekToLiveEdge()`](/api/features/playback/#seektoliveedge) | Jump to the newest audio (no-op for non-live tracks).             |
| [`setupPlayer({ retry })`](/guide/errors#automatic-retry)    | Automatic retry: 12 s first-connect / 2 min recovery budgets.     |
| [`retry()`](/guide/errors#manual-retry)                      | Restart from `'error'` with fresh budgets.                        |
| [`media.resolve`](/guide/browser#media-and-artwork)          | Build each (re)load's stream request — signed / short-lived URLs. |
| [`useOnline()`](/guide/network)                              | Connectivity for your own UI; playback reacts on its own.         |
