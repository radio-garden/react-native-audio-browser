# react-native-audio-browser

[![npm](https://img.shields.io/npm/v/react-native-audio-browser)](https://www.npmjs.com/package/react-native-audio-browser)
[![license](https://img.shields.io/npm/l/react-native-audio-browser)](LICENSE)

Full-featured React Native audio for production apps that span app screens, lock screens, CarPlay, Android Auto, voice controls, and the web, with one shared playback and browse model.

Built by former [react-native-track-player](https://github.com/doublesymmetry/react-native-track-player) maintainers and contributors, and developed for the official [Radio Garden](https://radio.garden) apps. Audio Browser takes that experience in a new direction: a browse-first architecture for the surfaces modern audio apps need.

In the car, audio apps are organized as a browse tree: tabs, nested folders, and playable items, often loaded on demand from your backend. Define the tree in JS, fetch it from your API, or resolve nodes lazily with callbacks. Audio Browser renders it as native CarPlay and Android Auto UI, with playback wired into the lock screen and system controls.

Use the same tree in your app UI, or keep it focused on CarPlay and Android Auto. Either way, [hooks](#hooks) expose playback, queue, and browse state for your own screens.

## Features

- **Browse-first architecture** — tabs, folders, routes, and playable items resolved from static data, JS callbacks, or your JSON API, then rendered natively on CarPlay and Android Auto.
- **Car cold start** — launch directly from CarPlay or Android Auto with the phone app closed. The browse tree and playback come up on the head unit without opening the app first.
- **Production playback** — queue, background audio, playback rate, interruptions, audio focus, and lock screen, notification, and headset controls.
- **Built on Nitro** — React Native's New Architecture, synchronous native calls, and no bridge overhead.
- **Real native-to-JS callbacks** — route resolvers, request transforms, gates, search, and track-load interception call into your JS and use what you return, instead of going through fire-and-forget bridge events.
- **Playback resumption** — persist the last session and resume after the app is killed, including the Android system play button and “play «App»” via Siri.
- **Live-stream ready** — HLS, ICY/Icecast, and progressive streams, with stall recovery, reconnects, fresh URL resolution, and live-edge seeking. See [Streaming](#streaming).
- **React hooks** — reactive playback, queue, browse, progress, sleep timer, and car-connection state for your app UI. See [Hooks](#hooks).
- **Now Playing** — metadata, artwork, timed metadata, chapter metadata, lock screen updates, and media-session integration.
- **Favorites** — favorite the current track from the Now Playing heart button or by voice, ask Siri to “play my favorites” or search within them, and keep favorited state in sync across surfaces — even when your app stores favorites by ID instead of stream URL.
- **Voice search** — Siri and Google Assistant route into one structured `search` source, so “play some jazz” can arrive as a genre filter instead of a raw query string.
- **Access gates** — put browse and search behind a paywall, login, or region wall with per-request resolvers.
- **Request and artwork control** — rewrite outbound requests, add auth headers, shape URLs, and customize artwork loading.
- **Web support** — the same `AudioBrowser` API works with `react-native-web` for playback, browse, search, and sleep timer.
- **Platform extras** — AirPlay and output routing on iOS, Android battery-resume handling, sleep timer, and Android equalizer support.

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
