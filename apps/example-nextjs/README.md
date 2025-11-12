# TrackPlayer Next.js Example

This example demonstrates how to use `react-native-audio-browser` in a Next.js web application using React Native Web.

## Features

- ✅ Full audio playback control (play, pause, skip)
- ✅ Progress tracking with visual progress bar
- ✅ Track metadata display with artwork
- ✅ Responsive UI using React Native components
- ✅ Server-side rendering compatible (uses dynamic imports)
- ✅ Web-specific player implementation using HTML5 Audio/Shaka Player

## Quick Start

### From the Monorepo Root

```bash
# Install dependencies
yarn install

# Build the library
yarn prepare

# Start the Next.js dev server
cd apps/example-nextjs
yarn dev
```

Open [http://localhost:3000](http://localhost:3000) to see the audio player.

## Running the App

You can:

- **Start in development mode:**

```bash
yarn dev
```

- **Create and run a production build:**

```bash
yarn build
yarn start
```

- **Analyze the bundle** (opens report in browser):

```bash
yarn build:analyze
```

- **Build without minification** (useful for debugging):

```bash
yarn build:disable-minification
```

## Project Structure

```
apps/example-nextjs/
├── components/
│   ├── AudioPlayer.js           # Main player UI component
│   └── TrackPlayerProvider.js   # Player setup and initialization
├── pages/
│   ├── _app.js                  # Next.js app wrapper
│   ├── _document.js             # HTML document structure
│   └── index.js                 # Home page with player
├── next.config.js               # Next.js configuration
└── package.json                 # Dependencies
```

## How It Works

### Server-Side Rendering

The app uses Next.js dynamic imports to avoid SSR issues:

```javascript
const AudioPlayer = dynamic(() => import('../components/AudioPlayer'), {
  ssr: false,
});
```

This ensures TrackPlayer only loads client-side where browser APIs are available.

### Web Implementation

- **Native (iOS/Android)**: Platform-specific audio APIs
- **Web**: HTML5 Audio API or Shaka Player for HLS/DASH

The web implementation is automatically selected in browser environments.

## Adding Custom Tracks

Edit `components/TrackPlayerProvider.js`:

```javascript
const tracks = [
  {
    url: 'https://example.com/audio.mp3',
    title: 'My Track',
    artist: 'Artist Name',
    artwork: 'https://example.com/artwork.jpg',
    duration: 120,
  },
];
```

## Components

### TrackPlayerProvider

Initializes TrackPlayer and loads the queue:

```javascript
<TrackPlayerProvider>
  <AudioPlayer />
</TrackPlayerProvider>
```

### AudioPlayer

Main UI with:

- Track info and artwork
- Progress bar
- Playback controls (play/pause/skip)

Uses TrackPlayer hooks:

- `useActiveTrack()` - Current track info
- `useProgress()` - Playback progress
- `usePlaybackState()` - Player state

## Troubleshooting

### Audio Not Playing

1. Check browser console for errors
2. Verify audio URLs are accessible (check CORS)
3. Some browsers require user interaction before playing

### Module Resolution Issues

```bash
# From root
yarn install
yarn prepare

# Clear Next.js cache
cd apps/example-nextjs
rm -rf .next
yarn dev
```

## Web vs Native Differences

| Feature              | Web               | Native             |
| -------------------- | ----------------- | ------------------ |
| Background playback  | Limited           | Full               |
| Lock screen controls | No                | Yes                |
| Notifications        | No                | Yes                |
| Car integration      | No                | Yes                |
| Audio formats        | Browser-dependent | Platform-dependent |

## Resources

- [Next.js Documentation](https://nextjs.org/docs)
- [React Native Web](https://necolas.github.io/react-native-web/)
