---
description: 'Install react-native-audio-browser and its react-native-nitro-modules peer dependency, complete the iOS and Android native setup, and add shaka-player if you target the browser.'
---

# Getting Started

## Installation

The library requires **React Native 0.76 or newer** (with the New Architecture enabled) and React 18.2+, and runs on iOS 16+ and Android API 24+. `react-native-nitro-modules` is a required peer dependency — install it alongside:

::: code-group

```bash [npm]
npm install react-native-audio-browser react-native-nitro-modules
```

```bash [yarn]
yarn add react-native-audio-browser react-native-nitro-modules
```

```bash [pnpm]
pnpm add react-native-audio-browser react-native-nitro-modules
```

```bash [bun]
bun add react-native-audio-browser react-native-nitro-modules
```

:::

On iOS, install the pods:

```bash
cd ios && pod install
```

For playback to continue in the background on iOS, add the `audio` background mode to your app's `Info.plist`:

```xml
<key>UIBackgroundModes</key>
<array>
  <string>audio</string>
</array>
```

Android links automatically.

For web (`react-native-web`), also install [`shaka-player`](https://github.com/shaka-project/shaka-player) 4.7.9 or newer. It is an optional peer dependency that the web player loads during `setupPlayer()`. Native builds don't need it.

::: code-group

```bash [npm]
npm install shaka-player
```

```bash [yarn]
yarn add shaka-player
```

```bash [pnpm]
pnpm add shaka-player
```

```bash [bun]
bun add shaka-player
```

:::

## Basic Setup

```ts
import { setupPlayer } from 'react-native-audio-browser'

// Initialize the player once at startup
await setupPlayer()
```

> The library exposes both named exports (`import { setupPlayer }`) and a default namespace (`import AudioBrowser from '…'; AudioBrowser.setupPlayer()`). The guides use the named form throughout.

## Web

The same API runs in the browser. On web the library plays through an HTML `<audio>` element driven by [Shaka Player](https://github.com/shaka-project/shaka-player), the optional peer dependency from [Installation](#installation).

Your bundler must resolve `.web.ts` / `.web.js` files ahead of plain ones, as react-native-web setups do, and map `react-native` to `react-native-web`; on web the library uses only `AppState` and `Image` from it. On the server (no `window`) `setupPlayer()` creates no player, so call it in the browser.

Playback, the queue, browse, search and the sleep timer work as on native. Now Playing metadata goes to the browser's Media Session API, so the system's media controls, media keys and Bluetooth buttons show the track and drive the player through the same [remote controls](/guide/remote-controls) handlers.

What web lacks:

- No CarPlay or Android Auto: the [Gate](/guide/gate) does nothing and `isCarConnected()` is always `false`.
- The [equalizer](/guide/equalizer), [battery](/guide/battery) and [audio output](/guide/audio-output) APIs return their empty values (`undefined`, `false`, `'unrestricted'`) and their setters do nothing. `getSystemVolume()` is always `1` and `setSystemVolume()` does nothing.
- No automatic retry (manual `retry()` works) and no timed metadata, neither ICY nor HLS ID3; see [Live Streams](/guide/live-streams#dropouts-stalls-and-retry).

## Next Steps

- [Basic Usage](/guide/basic-usage) - Learn how to play audio and build navigation trees
- [Android Auto](/guide/android-auto) - Set up Android Auto integration
- [CarPlay](/guide/carplay) - Set up CarPlay integration
