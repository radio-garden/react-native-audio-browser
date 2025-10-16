# react-native-audio-browser

A modern React Native audio framework featuring a browser-like navigation tree that can be defined manually or through JSON endpoints, built from the ground up with first class support for Android Auto and Apple Car Play.

## Installation

```bash
npm install react-native-audio-browser
# or
yarn add react-native-audio-browser
```

## Quick Start

```typescript
import React from 'react';
import { configure, useAudioBrowser } from 'react-native-audio-browser';

// Configure on app start
configure({
  api: 'https://api.radiogarden.com/api/v2',
  tree: [
    { title: 'Browse', path: '/browse', platforms: ['android-auto', 'carplay', 'mobile'] },
    { title: 'Search', path: '/search', platforms: ['android-auto', 'carplay', 'mobile'] },
    {
      title: 'Favorites',
      path: '/favorites',
      handler: async (path) => ({
        url: path,
        title: 'My Favorites',
        children: [
          { url: '/station/1', title: 'Radio 1', src: 'https://stream.url' }
        ]
      })
    }
  ]
});

function AudioBrowserScreen() {
  const {
    currentNode,
    currentPath,
    navigate,
    goBack
  } = useAudioBrowser();

  return (
    // Your UI here
  );
}
```

## API Reference

### `configure(config: AudioBrowserConfig)`

Sets up the audio browser with your configuration.

**Parameters:**
- `api?: string` - Base URL for HTTP requests
- `locale?: string` - Locale for internationalization
- `tree?: TreeNode[]` - Navigation tree structure
- `handlers?: Record<string, DataHandler>` - Custom data handlers

### `useAudioBrowser()`

React hook that provides audio browser state and actions.

**Returns:**
- `currentPath: string` - Current navigation path
- `currentNode: MediaList | null` - Current media list data
- `canGoBack: boolean` - Whether back navigation is possible
- `isConnectedToAndroidAuto: boolean` - Android Auto connection status
- `isConnectedToCarPlay: boolean` - CarPlay connection status
- `navigate(path: string): void` - Navigate to a path
- `goBack(): void` - Navigate back
- `refresh(): Promise<void>` - Refresh current data

### Direct Native Access

For advanced use cases, you can access the native module directly:

```typescript
import { AudioBrowser } from 'react-native-audio-browser';

// Sync operations
AudioBrowser.setAPI('https://api.example.com');
AudioBrowser.navigate('/browse/rock');
const currentPath = AudioBrowser.getCurrentPath();

// Async operations
const mediaList = await AudioBrowser.loadChildren('/browse');
```

## Tree Node Structure

```typescript
interface TreeNode {
  title: string;                    // Display name
  path: string;                     // Navigation path/endpoint
  icon?: string;                    // Icon identifier
  handler?: string | DataHandler;   // Custom data handler
  platforms?: Platform[];           // Supported platforms
  children?: TreeNode[];            // Static child nodes
}

type Platform = 'android-auto' | 'carplay' | 'mobile';
type DataHandler = (path: string) => Promise<MediaList>;
```

## Media Types

```typescript
interface MediaList {
  url: string;
  title: string;
  subtitle?: string;
  children: (MediaItem | MediaLink | MediaItemSection)[];
  style?: 'list' | 'grid';
  playable?: boolean;
}

interface MediaItem {
  url: string;
  title: string;
  subtitle?: string;
  src: string;     // Playable URL
}

interface MediaLink {
  url: string;
  title: string;
  subtitle?: string;
  href: string;    // Navigation URL
  playable?: boolean;
}
```

## Android Auto & CarPlay Support

The library automatically handles Android Auto and CarPlay integration:

- **4-tab limit**: Android Auto restricts root navigation to 4 tabs maximum
- **Platform filtering**: Use `platforms` array to show/hide nodes per platform
- **Thread safety**: All operations are thread-safe and optimized for car environments
- **Voice commands**: Proper metadata for voice navigation

## Custom Handlers

Define custom data sources for specific paths:

```typescript
const getFavorites = async (path: string) => {
  const favorites = await AsyncStorage.getItem('favorites');
  return {
    url: path,
    title: 'My Favorites',
    children: JSON.parse(favorites || '[]')
  };
};

configure({
  tree: [
    { title: 'Favorites', path: '/favorites', handler: getFavorites }
  ]
});
```

## License

MIT
