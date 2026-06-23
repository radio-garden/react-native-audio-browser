# react-native-audio-browser

[![npm](https://img.shields.io/npm/v/react-native-audio-browser)](https://www.npmjs.com/package/react-native-audio-browser)
[![license](https://img.shields.io/npm/l/react-native-audio-browser)](LICENSE)

Audio Browser (`react-native-audio-browser`) is a React Native audio library built around dynamic Android Auto and CarPlay navigation trees. From the team that maintained [react-native-track-player](https://github.com/doublesymmetry/react-native-track-player), and developed for [Radio Garden](https://radio.garden)'s official apps.

A CarPlay or Android Auto app navigates a browse tree — tabs, folders, and lists, usually lazy-loaded from a backend. This library makes that tree the core primitive: define it as static data, or resolve it on demand from JS callbacks or your JSON API, and it renders and plays natively on CarPlay, Android Auto, and the lock screen.

## Features

- **Dynamic browse tree** — tabs and routes resolved from your JSON API or JS callbacks, rendered natively on CarPlay and Android Auto.
- **Built on Nitro** — New Architecture, synchronous native calls, no bridge overhead.
- **Callback-driven** — the library calls *into* your code at decision points (route resolvers, request transforms, gating, track-load interception) and uses what you return. Real native-to-JS callbacks via Nitro — no fire-and-forget event-emitter plumbing.
- **Full playback** — queue, background audio, playback rate, and lock-screen / notification / headset controls.
- **Playback resumption** — persists the last session and resumes it after the app is killed: the system play button on Android, "play «App»" via Siri on iOS.
- **Live-stream ready** — HLS and ICY streams, with stall recovery, reconnection, and live-edge seeking (see [Streaming](#streaming)).
- **React hooks** — reactive playback, queue, and browse state for your in-app UI (see [Hooks](#hooks)).
- **Now Playing** — metadata, artwork, and timed & chapter metadata.
- **Voice search** — Siri and Google Assistant funnel to one `search` source, with structured params: "play some jazz" arrives as a genre filter, not a raw query string.
- **Gate** — put browse and search behind a paywall, login, or region wall, with a per-request resolver.
- **Request & artwork control** — rewrite outbound requests (auth headers, URL shaping) with transforms, and control how artwork is fetched.
- **Web support** — the same `AudioBrowser` API runs on the web (`react-native-web`): playback, browse tree, search, and sleep timer.
- **Extras** — AirPlay and output routing (iOS), Android battery-resume handling, sleep timer, favorites, and equalizer (Android).

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
  tabs: [{ title: 'Browse', url: '/browse' }],
  routes: {
    // Every path under /browse is fetched from your API, on demand.
    '/browse/**': { baseUrl: 'https://api.example.com' },
  },
})
```

Each route returns a page of children — a child with a `url` is a folder, one with a `src` is a playable track (plus optional `artist`, `artwork`, …):

```jsonc
// GET https://api.example.com/browse/jazz  →
{
  "url": "/browse/jazz",
  "title": "Jazz",
  "children": [
    { "title": "Smooth Floret FM", "url": "/browse/jazz/floret-fm" },           // url → open as a folder
    { "title": "The Stalk 88.5", "src": "https://stream.example.com/stalk.mp3" } // src → play this stream
  ]
}
```

The player works standalone, too — no browse tree required:

```ts
import AudioBrowser from 'react-native-audio-browser'

await AudioBrowser.setupPlayer()
AudioBrowser.setQueue([{ title: 'Track', artist: 'Artist', src: 'https://example.com/track.mp3' }])
AudioBrowser.play()
```

## Hooks

Reactive hooks for your in-app UI — they update automatically as playback, the queue, and browse state change. Call `setupPlayer()` once at startup, before rendering any component that uses them.

| Hook | Returns |
| --- | --- |
| `usePlayingState()` | `{ playing, buffering }` |
| `useProgress()` | `{ position, duration, buffered }` (seconds) |
| `useActiveTrack()` | the current `Track` |
| `useNowPlaying()` | now-playing metadata |
| `useQueue()` | the current queue |
| `useRepeatMode()` / `useShuffle()` | repeat / shuffle state |
| `useSleepTimer()` | sleep-timer state |
| `useCarConnected()` | whether a car is connected |
| `usePath()` / `useTabs()` / `useContent()` | current browse path, tabs, and content |

…and more — see the [API Reference](https://audiobrowser.dev/api/).

```tsx
import { View, Text, Button } from 'react-native'
import AudioBrowser, {
  usePlayingState,
  useProgress,
  useActiveTrack,
} from 'react-native-audio-browser'

function PlayerBar() {
  const { playing } = usePlayingState()        // { playing, buffering }
  const { position, duration } = useProgress() // in seconds
  const track = useActiveTrack()               // Track | undefined

  return (
    <View>
      <Text>{track?.title}</Text>
      <Text>{Math.floor(position)} / {Math.floor(duration)}s</Text>
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

Plus deeper per-platform hardening — Android stuck-stream recovery, optional disk caching, and opt-in AIA-chasing TLS (supports streams whose servers omit intermediate CA certificates); iOS media-services-reset and AirPlay stall recovery.

## Documentation

Full guides at [audiobrowser.dev](https://audiobrowser.dev):

- [Getting Started](https://audiobrowser.dev/guide/getting-started)
- [Basic Usage](https://audiobrowser.dev/guide/basic-usage)
- [CarPlay](https://audiobrowser.dev/guide/carplay)
- [Android Auto](https://audiobrowser.dev/guide/android-auto)
- [Search](https://audiobrowser.dev/guide/search)
- [Now Playing](https://audiobrowser.dev/guide/now-playing)
- [Gate](https://audiobrowser.dev/guide/gate)
- [API Reference](https://audiobrowser.dev/api/)

The example app in [`apps/example-native`](apps/example-native) browses archive.org's audio collection — in-app, on CarPlay, and on Android Auto — with search, favorites, and the gate.

## Support

Questions or bugs? [Open an issue](https://github.com/radio-garden/react-native-audio-browser/issues). Release notes live in the [changelog](CHANGELOG.md).

## Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md).

## License

[MIT](LICENSE) © the react-native-audio-browser contributors
