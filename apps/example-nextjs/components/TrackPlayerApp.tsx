'use client';

import { useEffect, useState } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';
import AudioBrowser, {
  onFavoriteChanged,
  notifyContentChanged,
  setFavorites,
  Track,
} from 'react-native-audio-browser';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { BrowserScreen, basicBrowserConfiguration } from 'common-app';

const styles = StyleSheet.create({
  container: {
    alignItems: 'center',
    flexGrow: 1,
    justifyContent: 'center',
    backgroundColor: '#1a1a1a',
    padding: 20,
  },
});

let favorites: Track[] = basicBrowserConfiguration._favorites;

// Load persisted favorites from localStorage on startup (browser-only)
if (typeof window !== 'undefined') {
  const persistedFavorites = localStorage.getItem('favorites');
  if (persistedFavorites) {
    favorites = JSON.parse(persistedFavorites) as Track[];
    // Sync with native favorites cache so heart buttons show correct state
    setFavorites(favorites.map((t) => t.src).filter(Boolean) as string[]);
  }
}

export default function TrackPlayerProvider() {
  const [isMounted, setIsMounted] = useState(false);

  useEffect(() => {
    const setup = async () => {
      await AudioBrowser.setupPlayer({});
      AudioBrowser.setPlayWhenReady(true);
      AudioBrowser.configureBrowser(basicBrowserConfiguration);

      // Handle favorite changes (heart button taps from app)
      onFavoriteChanged.addListener(({ track, favorited }) => {
        // Update our favorites array
        if (favorited) {
          if (!favorites.find((t) => t.src === track.src)) {
            // Strip url - it contains the original context (e.g., /library/radio?__trackId=...)
            // The library will regenerate the correct contextual URL when browsing favorites
            // eslint-disable-next-line @typescript-eslint/no-unused-vars
            const { url, groupTitle, ...trackWithoutUrl } = track;
            favorites.push(trackWithoutUrl as Track);
          }
        } else {
          favorites = favorites.filter((t) => t.src !== track.src);
        }
        favorites.sort((a, b) => a.title.localeCompare(b.title));

        // Update the browser configuration reference so /favorites route returns updated array
        basicBrowserConfiguration._favorites = favorites;

        // Persist to localStorage (browser-only)
        if (typeof window !== 'undefined') {
          localStorage.setItem('favorites', JSON.stringify(favorites));
        }

        // Notify browser that favorites content has changed
        notifyContentChanged('/favorites');
      });

      setIsMounted(true);
    };

    void setup();
  }, []);

  if (!isMounted) {
    return (
      <View style={styles.container}>
        <ActivityIndicator size="large" color="#1DB954" />
      </View>
    );
  }

  return (
    <SafeAreaProvider
      initialMetrics={{
        frame: { x: 0, y: 0, width: 0, height: 0 },
        insets: { top: 0, left: 0, right: 0, bottom: 0 },
      }}
    >
      <BrowserScreen />
    </SafeAreaProvider>
  );
}
