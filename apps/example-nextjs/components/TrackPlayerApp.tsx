'use client'

import { useEffect, useState } from 'react'
import { ActivityIndicator, Platform, StyleSheet, View } from 'react-native'
import AudioBrowser, {
  onFavoriteChanged,
  notifyContentChanged,
  setFavorites,
  updateOptions,
  type Track,
  type BrowserConfiguration
} from 'react-native-audio-browser'
import { SafeAreaProvider } from 'react-native-safe-area-context'
import {
  archiveLibrarySection,
  archiveRoutes
} from '../../example-native/src/api/archive-org'
import { authedMediaTransform } from '../../example-native/src/api/authed'
import {
  radioGardenLibrarySection,
  radioGardenMediaTransform,
  radioGardenRoutes
} from '../../example-native/src/api/radio-garden'
import { BrowserScreen } from '../screens'

const styles = StyleSheet.create({
  container: {
    alignItems: 'center',
    flexGrow: 1,
    justifyContent: 'center',
    backgroundColor: '#1a1a1a',
    padding: 20
  }
})

let favorites: Track[] = []

// Load persisted favorites from localStorage on startup (browser-only)
if (typeof window !== 'undefined') {
  const persistedFavorites = localStorage.getItem('favorites')
  if (persistedFavorites) {
    favorites = JSON.parse(persistedFavorites) as Track[]
    // Sync with native favorites cache so heart buttons show correct state
    setFavorites(favorites.map((t) => t.src).filter(Boolean) as string[])
  }
}

const configuration: BrowserConfiguration = {
  tabs: [
    {
      title: 'Library',
      path: '/library',
      artwork: Platform.select({
        ios: 'sf:music.note.list'
      })
    },
    {
      title: 'JSON API',
      path: '/api',
      artwork: Platform.select({
        ios: 'sf:server.rack'
      })
    },
    {
      title: 'Favorites',
      path: '/favorites',
      artwork: Platform.select({
        ios: 'sf:heart.fill'
      })
    }
  ],
  media: {
    // Shared with the native example rather than re-inlined, so the two can't
    // drift. Each returns the request untouched when the path isn't its own.
    async transform(request, params) {
      return authedMediaTransform(
        await radioGardenMediaTransform(request, params),
        params
      )
    }
  },
  routes: {
    // Shared with the native example, like the media transforms above.
    ...archiveRoutes,
    ...radioGardenRoutes,
    '/api/**': {
      baseUrl: 'http://localhost:3003'
    },
    '/favorites'() {
      return Promise.resolve({
        path: '/favorites',
        title: 'Favorites',
        children: favorites
      })
    },
    '/library': {
      path: '/library',
      title: 'Library',
      sections: [
        // Same sections, same order as the native example, from the same
        // shared modules — so the two apps cannot drift. The rntp.dev demo
        // files these replaced now 307 to www.rntp.dev and 404 there;
        // Archive.org is also what the `/api/authed` routes redirect to, so it
        // cannot rot independently of the rest of the app.
        archiveLibrarySection,
        radioGardenLibrarySection,
        // Local HLS fixture. Next serves `public/whip/` at `/whip/`, so the
        // manifest and all 17 segments are served by this app rather than a
        // third-party host — HLS manifest parsing, segment loading and seeking
        // stay testable offline, and can't rot when a remote URL moves.
        //
        // The manifest references its segments by relative name, so it needs no
        // rewriting. Neither media transform claims `/whip/`, so the src reaches
        // the player untouched.
        {
          title: 'HLS',
          children: [
            {
              id: 'whip-hls',
              title: 'Whip',
              src: '/whip/playlist.m3u8',
              artwork: '/whip/whip.png'
            }
          ]
        },
        {
          title: 'Other',
          children: [
            {
              src: 'https://traffic.libsyn.com/atpfm/atp545.mp3',
              title: 'Chapters'
            }
          ]
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
        if ('children' in source || 'sections' in source) {
          // A static page holds its tracks as plain children or in sections
          const children = [
            ...('children' in source ? (source.children ?? []) : []),
            ...('sections' in source
              ? (source.sections?.flatMap((section) => section.children) ?? [])
              : [])
          ]
          results.push(
            ...children.filter(
              (track) =>
                !!(['title', 'artist', 'album'] as const).find(
                  (field) => !!track[field]?.toLowerCase().includes(query)
                )
            )
          )
          if (source.title?.toLowerCase().includes(query)) {
            results.push({
              path: source.path,
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

    // Dedupe by src (for playable tracks) or path (for browsable items)
    const seen = new Set<string>()
    return results.filter((track) => {
      const key = track.src ?? track.path
      if (!key || seen.has(key)) return false
      seen.add(key)
      return true
    })
  },
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
  const [isMounted, setIsMounted] = useState(false)

  useEffect(() => {
    const setup = async () => {
      await AudioBrowser.setupPlayer({})
      // Now-playing buttons are a player option (iOS), not browser config
      updateOptions({
        ios: {
          carPlayNowPlayingButtons: ['favorite', 'repeat', 'playback-rate']
        }
      })
      AudioBrowser.setPlayWhenReady(true)
      AudioBrowser.configureBrowser(configuration)

      // Handle favorite changes (heart button taps from app)
      onFavoriteChanged.addListener(({ track, favorited }) => {
        // Update our favorites array
        if (favorited) {
          if (!favorites.find((t) => t.src === track.src)) {
            // Strip path - it contains the original context (e.g., /library/radio?__trackId=...)
            // The library will regenerate the correct contextual path when browsing favorites
            // eslint-disable-next-line @typescript-eslint/no-unused-vars
            const { path, ...trackWithoutPath } = track
            favorites.push(trackWithoutPath as Track)
          }
        } else {
          favorites = favorites.filter((t) => t.src !== track.src)
        }
        favorites.sort((a, b) => a.title.localeCompare(b.title))

        // Persist to localStorage (browser-only)
        if (typeof window !== 'undefined') {
          localStorage.setItem('favorites', JSON.stringify(favorites))
        }

        // Notify browser that favorites content has changed
        notifyContentChanged('/favorites')
      })

      setIsMounted(true)
    }

    void setup()
  }, [])

  if (!isMounted) {
    return (
      <View style={styles.container}>
        <ActivityIndicator size="large" color="#1DB954" />
      </View>
    )
  }

  return (
    <SafeAreaProvider
      initialMetrics={{
        frame: { x: 0, y: 0, width: 0, height: 0 },
        insets: { top: 0, left: 0, right: 0, bottom: 0 }
      }}
    >
      <BrowserScreen />
    </SafeAreaProvider>
  )
}
