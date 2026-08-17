# react-native-audio-browser

[![npm](https://img.shields.io/npm/v/react-native-audio-browser)](https://www.npmjs.com/package/react-native-audio-browser)
[![license](https://img.shields.io/npm/l/react-native-audio-browser)](LICENSE)

Full-featured React Native audio for production apps that span app screens, lock screens, CarPlay, Android Auto, voice controls, and the web, with one shared playback and browse model.

Built by former [react-native-track-player](https://github.com/doublesymmetry/react-native-track-player) maintainers and contributors, and developed for the official [Radio Garden](https://radio.garden) apps. Audio Browser takes that experience in a new direction: a browse-first architecture for the surfaces modern audio apps need.

> **Coming from react-native-track-player?** The playback and queue API is intentionally familiar, so most code ports over with small changes — see the [migration guide](https://audiobrowser.dev/guide/migrating-from-track-player).

In the car, audio apps are organized as a browse tree: tabs, nested lists, and playable items, often loaded on demand from your backend. Define the tree in JS, fetch it from your API, or resolve it lazily with callbacks. Audio Browser renders it as native CarPlay and Android Auto UI, with playback wired into the lock screen and system controls.

Use the same tree in your app UI, or keep it focused on CarPlay and Android Auto. Either way, [hooks](#hooks) expose playback, queue, and browse state for your own screens.

## Features

- **[Browse-first architecture](https://audiobrowser.dev/guide/browser)** — tabs, nested lists, routes, and playable items resolved from static data, JS callbacks, or your JSON API, then rendered natively on CarPlay and Android Auto.
- **Car cold start** — launch directly from CarPlay or Android Auto with the phone app closed. The browse tree and playback come up on the head unit without opening the app first.
- **Production [playback](https://audiobrowser.dev/guide/playback)** — [queue](https://audiobrowser.dev/guide/queue), background audio, playback rate, interruptions, audio focus, and lock screen, notification, and headset controls.
- **Built on Nitro** — React Native's New Architecture, synchronous native calls, and no bridge overhead.
- **Real native-to-JS callbacks** — route resolvers, request transforms, gates, search, and track-load interception call into your JS and use what you return, instead of going through fire-and-forget bridge events.
- **Playback resumption** — persist the last session and resume after the app is killed, including the Android system play button and “play «App»” via Siri.
- **[Live-stream ready](#streaming)** — HLS, ICY/Icecast, and progressive streams, with stall recovery, reconnects, fresh URL resolution, and live-edge seeking.
- **[React hooks](#hooks)** — reactive playback, queue, browse, progress, sleep timer, and car-connection state for your app UI.
- **[Now Playing](https://audiobrowser.dev/guide/now-playing)** — metadata, artwork, [timed metadata, chapter metadata](https://audiobrowser.dev/guide/metadata), lock screen updates, and media-session integration.
- **[Favorites](https://audiobrowser.dev/guide/favorites)** — favorite the current track from the Now Playing heart button or by voice, ask Siri to “play my favorites” or search within them, and keep favorited state in sync across surfaces — even when your app stores favorites by ID instead of stream URL.
- **[Voice search](https://audiobrowser.dev/guide/search)** — Siri and Google Assistant route into one structured `search` source, so “play some jazz” can arrive as a genre filter instead of a raw query string.
- **[Access gates](https://audiobrowser.dev/guide/gate)** — put browse and search behind a paywall, login, or region wall with per-request resolvers.
- **Request and [artwork](https://audiobrowser.dev/guide/artwork) control** — rewrite outbound requests, add auth headers, shape URLs, and customize artwork loading.
- **Web support** — the same `AudioBrowser` API works with `react-native-web` for playback, browse, search, and sleep timer.
- **Platform extras** — [AirPlay and output routing](https://audiobrowser.dev/guide/audio-output) on iOS, [Android battery-resume handling](https://audiobrowser.dev/guide/battery), [sleep timer](https://audiobrowser.dev/guide/sleep-timer), and [Android equalizer support](https://audiobrowser.dev/guide/equalizer).

## Requirements

- React Native **0.76+** with the **New Architecture** (Fabric + TurboModules)
- iOS **16+** · Android **API 23+**
- [`react-native-nitro-modules`](https://nitro.margelo.com) (peer dependency)

## Installation

```sh
npm install react-native-audio-browser react-native-nitro-modules
```

iOS:

```sh
cd ios && pod install
```

Android links automatically.

CarPlay and Android Auto need extra native setup (entitlements, manifest entries) — see the [CarPlay](https://audiobrowser.dev/guide/carplay) and [Android Auto](https://audiobrowser.dev/guide/android-auto) guides.

## Quick Start

Point the library at your API and it drives the whole CarPlay / Android Auto browse tree — lazily, as the listener navigates:

```ts
import AudioBrowser from 'react-native-audio-browser'

await AudioBrowser.setupPlayer()

AudioBrowser.configureBrowser({
  tabs: [{ title: 'Browse', path: '/browse' }],
  routes: {
    // Every path under /browse is fetched from your API, on demand.
    '/browse/**': { baseUrl: 'https://api.example.com' }
  }
})
```

Each route returns a page of children — a child with a `path` is browsable (open it for more), one with a `src` is a playable track (plus optional `artist`, `artwork`, …):

```jsonc
// GET https://api.example.com/browse/jazz  →
{
  "path": "/browse/jazz",
  "title": "Jazz",
  "children": [
    { "title": "Smooth Floret FM", "path": "/browse/jazz/floret-fm" }, // path → open for children
    { "title": "The Stalk 88.5", "src": "https://stream.example.com/stalk.mp3" } // src → play this stream
  ]
}
```

The player works standalone, too — no browse tree required:

```ts
import AudioBrowser from 'react-native-audio-browser'

await AudioBrowser.setupPlayer()
AudioBrowser.setQueue([
  { title: 'Track', artist: 'Artist', src: 'https://example.com/track.mp3' }
])
AudioBrowser.play()
```

## Hooks

Reactive hooks for your in-app UI — they update automatically as playback, the queue, and browse state change. Call `setupPlayer()` once at startup, before rendering any component that uses them.

| Hook                                       | Returns                                                              |
| ------------------------------------------ | -------------------------------------------------------------------- |
| `usePlayingState()`                        | `{ playing, buffering }`                                             |
| `useProgress()`                            | `{ position, duration, buffered }` (seconds)                         |
| `useActiveTrack()`                         | the current `Track`                                                  |
| `useNowPlaying()`                          | now-playing metadata                                                 |
| `useQueue()`                               | the current queue                                                    |
| `useRepeatMode()` / `useShuffle()`         | repeat / shuffle state                                               |
| `useSleepTimer()`                          | sleep-timer state                                                    |
| `useCarConnected()`                        | whether a car is connected                                           |
| `usePath()` / `useTabs()` / `useContent()` | current browse path, tabs, and the resolved page at the current path |

…and more — see the [Hooks guide](https://audiobrowser.dev/guide/hooks) and the [API Reference](https://audiobrowser.dev/api/).

```tsx
import { View, Text, Button } from 'react-native'
import AudioBrowser, {
  usePlayingState,
  useProgress,
  useActiveTrack
} from 'react-native-audio-browser'

function PlayerBar() {
  const { playing } = usePlayingState() // { playing, buffering }
  const { position, duration } = useProgress() // in seconds
  const track = useActiveTrack() // Track | undefined

  return (
    <View>
      <Text>{track?.title}</Text>
      <Text>
        {Math.floor(position)} / {Math.floor(duration)}s
      </Text>
      <Button
        title={playing ? 'Pause' : 'Play'}
        onPress={() => AudioBrowser.togglePlayback()}
      />
    </View>
  )
}
```

## Streaming

Optimizations for live radio and long-running streams:

- **Auto-reconnect** — opt in with `setupPlayer({ retry: true })`. On a network error the player retries with exponential backoff (~1→5s, up to a 2-minute window), waits out offline gaps, and reconnects the instant the network returns.
- **Fresh URLs on reconnect** — if you resolve sources with a `media.resolve` callback, every retry re-runs it, so expired signed / token-authenticated stream URLs refresh on their own.
- **Live edge** — `seekToLiveEdge()` jumps back to live: the DVR-window end for seekable HLS, a clean reconnect for non-seekable ICY/Icecast.
- **ICY & chapter metadata** — Shoutcast `StreamTitle` and chapter markers surface via `onTimedMetadata` / `onChapterMetadata`, and feed the lock screen.
- **Plays nicely** — pauses cleanly on calls, audio-focus loss, and headphone/Bluetooth disconnect, and resumes when it should.
- **HLS, ICY/Icecast, and progressive** everywhere; DASH on Android and web.

Plus deeper per-platform hardening — Android stuck-stream recovery, optional disk caching, and opt-in [AIA-chasing TLS](https://audiobrowser.dev/guide/android-certificates) (supports streams whose servers omit intermediate CA certificates); iOS media-services-reset and AirPlay stall recovery.

## Documentation

Full guides and the complete [API Reference](https://audiobrowser.dev/api/) live at [audiobrowser.dev](https://audiobrowser.dev).

**Start here** — [Getting Started](https://audiobrowser.dev/guide/getting-started) · [Basic Usage](https://audiobrowser.dev/guide/basic-usage) · [Configuration](https://audiobrowser.dev/guide/configuration) · [Track](https://audiobrowser.dev/guide/track) · [Hooks](https://audiobrowser.dev/guide/hooks) · [Migrating from react-native-track-player](https://audiobrowser.dev/guide/migrating-from-track-player)

**Player** — [Playback](https://audiobrowser.dev/guide/playback) · [Queue](https://audiobrowser.dev/guide/queue) · [Now Playing](https://audiobrowser.dev/guide/now-playing) · [Metadata](https://audiobrowser.dev/guide/metadata) · [Remote Controls](https://audiobrowser.dev/guide/remote-controls) · [Errors](https://audiobrowser.dev/guide/errors) · [Artwork](https://audiobrowser.dev/guide/artwork)

**Browse** — [Browser](https://audiobrowser.dev/guide/browser) · [Search](https://audiobrowser.dev/guide/search) · [Favorites](https://audiobrowser.dev/guide/favorites) · [Gate](https://audiobrowser.dev/guide/gate)

**Automotive** — [Overview](https://audiobrowser.dev/guide/automotive) · [CarPlay](https://audiobrowser.dev/guide/carplay) · [Android Auto](https://audiobrowser.dev/guide/android-auto)

**Extras** — [Sleep Timer](https://audiobrowser.dev/guide/sleep-timer) · [Equalizer](https://audiobrowser.dev/guide/equalizer) · [Audio Output](https://audiobrowser.dev/guide/audio-output) · [Network](https://audiobrowser.dev/guide/network) · [Battery](https://audiobrowser.dev/guide/battery)

**Troubleshooting** — [Networking in native callbacks](https://audiobrowser.dev/guide/native-callback-fetch) · [Android SSL / trust anchors](https://audiobrowser.dev/guide/android-certificates)

The example app in [`apps/example-native`](https://github.com/radio-garden/react-native-audio-browser/tree/main/apps/example-native) browses archive.org's audio collection — in-app, on CarPlay, and on Android Auto — with search, favorites, and the gate.

### For coding agents

Every page is also served as raw Markdown — append `.md` to any URL, e.g.
[`audiobrowser.dev/guide/queue.md`](https://audiobrowser.dev/guide/queue.md).

- [`/llms.txt`](https://audiobrowser.dev/llms.txt) — an index of every page with a one-line summary, to pick what to read.
- [`/llms-full.txt`](https://audiobrowser.dev/llms-full.txt) — every guide in one file.

The same guide Markdown ships in the package, so it can be read straight off disk without a network fetch: `node_modules/react-native-audio-browser/website/guide/`.

## Support

Questions or bugs? [Open an issue](https://github.com/radio-garden/react-native-audio-browser/issues). Release notes live in the [changelog](CHANGELOG.md).

## Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md).

## License

[MIT](LICENSE) © the react-native-audio-browser contributors
