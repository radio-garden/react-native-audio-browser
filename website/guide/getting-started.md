# Getting Started

## Installation

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

## Basic Setup

```ts
import { setupPlayer } from 'react-native-audio-browser'

// Initialize the player once at startup
await setupPlayer()
```

> The library exposes both named exports (`import { setupPlayer }`) and a default namespace (`import AudioBrowser from '…'; AudioBrowser.setupPlayer()`). The guides use the named form throughout.

## Next Steps

- [Basic Usage](/guide/basic-usage) - Learn how to play audio and build navigation trees
- [Android Auto](/guide/android-auto) - Set up Android Auto integration
- [CarPlay](/guide/carplay) - Set up CarPlay integration
