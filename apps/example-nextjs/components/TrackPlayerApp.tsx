'use client';

import { useEffect, useState } from 'react';
import { ActivityIndicator, Platform, StyleSheet, View } from 'react-native';
import AudioBrowser, {
  onFavoriteChanged,
  notifyContentChanged,
  setFavorites,
  Track,
  type BrowserConfiguration,
} from 'react-native-audio-browser';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { BrowserScreen } from '../screens';

const styles = StyleSheet.create({
  container: {
    alignItems: 'center',
    flexGrow: 1,
    justifyContent: 'center',
    backgroundColor: '#1a1a1a',
    padding: 20,
  },
});

let favorites: Track[] = [];

// Load persisted favorites from localStorage on startup (browser-only)
if (typeof window !== 'undefined') {
  const persistedFavorites = localStorage.getItem('favorites');
  if (persistedFavorites) {
    favorites = JSON.parse(persistedFavorites) as Track[];
    // Sync with native favorites cache so heart buttons show correct state
    setFavorites(favorites.map((t) => t.src).filter(Boolean) as string[]);
  }
}


const configuration: BrowserConfiguration = {
  tabs: [
    {
      title: 'Library',
      url: '/library',
      artwork: Platform.select({
        ios: 'sf:music.note.list'
      })
    },
    {
      title: 'JSON API',
      url: '/api',
      artwork: Platform.select({
        ios: 'sf:server.rack'
      })
    },
    {
      title: 'Favorites',
      url: '/favorites',
      artwork: Platform.select({
        ios: 'sf:heart.fill'
      }),
    }
  ],
  media: {
    async transform(request) {
      if (request.path && request.path.startsWith('/rg/')) {
        return {
          baseUrl: 'https://radio.garden/api/ara/content/listen',
          path: `${request.path.replace('/rg/', '')}/channel.mp3`
        }
      }
      return request
    }
  },
  routes: {
    '/api/**': {
      baseUrl: 'http://localhost:3003'
    },
    '/favorites'() {
      return Promise.resolve({
        url: '/favorites',
        title: 'Favorites',
        children: favorites
      })
    },
    '/library/playlists': {
      url: '/library/playlists',
      title: 'Radio Playlists',
      children: [
        {
          title: 'Independent Sounds',
          url: '/playlist/independent-sounds'
        },
        {
          title: 'Energetic Rhythms',
          url: '/playlist/energetic-rhythms'
        }
      ]
    },
    async '/playlist/{id}'({ routeParams }) {
      return {
        'independent-sounds': {
          title: 'Independent Sounds',
          url: '/api/playlist/independent-sounds',
          children: [
            { title: 'Radio is a Foreign Country', src: 'b35yEqjv', live: true },
            { title: 'NTS 1', src: 'wT9JJD4j', live: true },
            { title: 'Worldwide FM', src: '/rg/vfm-z7pR', live: true },
            { title: 'Kiosk Radio', src: '/rg/rTzlLOJp', live: true },
            { title: 'Rinse France', src: '/rg/39GkuKiS', live: true },
            { title: 'Radio 80000', src: '/rg/MBWk5Fmi', live: true },
            { title: 'Foundation FM', src: '/rg/QgsEUvYo', live: true },
            { title: 'Dublin Digital Radio', src: '/rg/Bv4OzWTA', live: true },
            { title: 'LYL Radio', src: '/rg/LINZ0-LZ', live: true }
          ]
        },
        'energetic-rhythms': {
          title: 'Energetic Rhythms',
          url: '/playlist/energetic-rhythms',
          children: [
            { title: 'Noods Radio', src: '/rg/TdAjNy_3', live: true },
            { title: 'Systrum Sistum - SSR2', src: '/rg/ftR_mtxU', live: true },
            { title: 'Radio.D59B', src: '/rg/GSLfbwH8', live: true },
            { title: 'Dublab DE', src: '/rg/IbYQwskl', live: true },
            { title: 'Operator Radio', src: '/rg/8Ls6E7wH', live: true },
            { title: 'datafruits', src: '/rg/nED7EFV4', live: true }
          ]
        }
      }[routeParams!.id]!
    },
    '/library': {
      url: '/library',
      title: 'Library',
      children: [
        {
          url: '/library/playlists',
          title: 'Radio Playlists',
          style: 'list'
        },
        {
          src: 'https://rntp.dev/example/Soul%20Searching.mp3',
          title: 'Soul Searching (Demo)',
          artist: 'David Chavez',
          artwork: 'https://rntp.dev/example/Soul%20Searching.jpeg',
          duration: 77,
          groupTitle: 'David Chavez'
        },
        {
          src: 'https://rntp.dev/example/Lullaby%20(Demo).mp3',
          title: 'Lullaby (Demo)',
          artist: 'David Chavez',
          artwork: 'https://rntp.dev/example/Lullaby%20(Demo).jpeg',
          duration: 71,
          groupTitle: 'David Chavez'
        },
        {
          src: 'https://rntp.dev/example/Rhythm%20City%20(Demo).mp3',
          title: 'Rhythm City (Demo)',
          artist: 'David Chavez',
          artwork: 'https://rntp.dev/example/Rhythm%20City%20(Demo).jpeg',
          duration: 106,
          groupTitle: 'David Chavez'
        },
        {
          src: 'https://rntp.dev/example/hls/whip/playlist.m3u8',
          title: 'Whip (m3u8 HLS Stream)',
          artist: 'prazkhanal',
          artwork: 'https://rntp.dev/example/hls/whip/whip.jpeg',
          groupTitle: 'Other'
        },
        {
          src: 'https://traffic.libsyn.com/atpfm/atp545.mp3',
          title: 'Chapters',
          groupTitle: 'Other'
        }
      ]
    }
  },

  // A somewhat convoluted search implementation that looks for the query in titles
  // and artists of all routes' children as well as the title of the routes
  // themselves. Try searching for "radio", "kutex", "david", "soul", etc - but also
  // for "favorites" and "library" to see that route titles are also searched.
  // (Normally you would want to search a backend or local database instead)
  async search({ query }) {
    query = query.toLowerCase()
    const results = Object.values(configuration.routes ?? {}).reduce<Track[]>(
      (results, source) => {
        if ('children' in source) {
          results.push(
            ...(source.children?.filter(
              (track) =>
                !!(['title', 'artist', 'album'] as const).find(
                  (field) => !!track[field]?.toLowerCase().includes(query)
                )
            ) ?? [])
          )
          if (source.title?.toLowerCase().includes(query)) {
            results.push({
              url: source.url,
              title: source.title,
              artwork: source.artwork,
              artist: source.artist,
              album: source.album
            })
          }
        }
        return results
      },
      []
    )

    // Dedupe by src (for playable tracks) or url (for browsable items)
    const seen = new Set<string>()
    return results.filter((track) => {
      const key = track.src ?? track.url
      if (!key || seen.has(key)) return false
      seen.add(key)
      return true
    })
  },
  carPlayNowPlayingButtons: ['favorite', 'repeat', 'playback-rate'],

  // Customize navigation error messages (used by CarPlay and available via useFormattedNavigationError)
  formatNavigationError({ error, defaultFormatted, path }) {
    // Custom message for local server routes when server isn't running
    if (error.code === 'network-error' && path.startsWith('/api')) {
      return {
        title: 'Api Example Server Not Running',
        message: 'Start the local server with: yarn api-server'
      }
    }

    // Use the default formatting for other error types
    return defaultFormatted
  }
}

export default function TrackPlayerApp() {

  const [isMounted, setIsMounted] = useState(false);

  useEffect(() => {
    const setup = async () => {
      await AudioBrowser.setupPlayer({});
      AudioBrowser.setPlayWhenReady(true);
      AudioBrowser.configureBrowser(configuration);

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
