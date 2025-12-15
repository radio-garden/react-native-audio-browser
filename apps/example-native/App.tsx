import React from 'react'
import { StyleSheet } from 'react-native'
import AudioBrowser, {
  BrowserConfiguration,
  getActiveTrack,
  notifyContentChanged,
  onFavoriteChanged,
  onPlaybackMetadata,
  setFavorites,
  setPlayWhenReady,
  Track,
  updateNowPlaying
} from 'react-native-audio-browser'
import { createMMKV } from 'react-native-mmkv'
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context'
import { BatteryWarning } from './src/components/BatteryWarning'
import { BrowserScreen } from './src/screens/BrowserScreen'
import { throttle } from './src/utils/throttle'

const storage = createMMKV()
let favorites: Track[] = []

// Load persisted favorites on startup
const persistedFavorites = storage.getString('favorites')
if (persistedFavorites) {
  favorites = JSON.parse(persistedFavorites) as Track[]
  // Sync with native favorites cache so heart buttons show correct state
  setFavorites(favorites.map((t) => t.src).filter(Boolean) as string[])
}

void AudioBrowser.setupPlayer().then(() => {
  setPlayWhenReady(true)

  // Configure player capabilities and notification button layout
  AudioBrowser.updateOptions({
    capabilities: [
      'play',
      'pause',
      'skip-to-next',
      'skip-to-previous',
      'seek-to',
      'favorite'
    ],
    android: {
      // Explicit notification button layout:
      // - Back/Forward: skip buttons as primary
      // - Overflow: favorite button (heart icon)
      notificationButtons: {
        back: 'skip-to-previous',
        forward: 'skip-to-next',
        overflow: ['favorite']
      }
    }
  })

  // Handle favorite changes (heart button taps from notification/Android Auto/app)
  // Native side has already updated the track and UI - we just need to persist
  onFavoriteChanged.addListener(({ track, favorited }) => {
    // Update our favorites array
    if (favorited) {
      if (!favorites.find((t) => t.src === track.src)) {
        // Strip url - it contains the original context (e.g., /library/radio?__trackId=...)
        // The library will regenerate the correct contextual URL when browsing favorites
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        const { url, groupTitle, ...trackWithoutUrl } = track
        favorites.push(trackWithoutUrl as Track)
      }
    } else {
      favorites = favorites.filter((t) => t.src !== track.src)
    }
    favorites.sort((a, b) => a.title.localeCompare(b.title))

    // Persist to storage
    storage.set('favorites', JSON.stringify(favorites))

    // Notify browser that favorites content has changed
    notifyContentChanged('/favorites')
  })

  // Update now playing from stream metadata (ICY/ID3 tags from live radio)
  // Throttle updates - skip leading so users see station name first,
  // but show the last metadata after the 2s window
  const updateNowPlayingThrottled = throttle(updateNowPlaying, 4000, {
    leading: false,
    trailing: true
  })

  onPlaybackMetadata.addListener((metadata) => {
    const track = getActiveTrack()

    // Build artist line from stream metadata (e.g., "Song Title - Artist Name")
    const artistLine = [metadata.title, metadata.artist]
      .filter(Boolean)
      .join(' - ')

    if (artistLine) {
      updateNowPlayingThrottled({
        title: track?.title, // Keep station name
        artist: artistLine
      })
    } else {
      updateNowPlaying(null) // Revert to track metadata
    }
  })
})

const configuration: BrowserConfiguration = {
  tabs: [
    {
      title: 'Library',
      url: '/library'
    },
    {
      title: 'JSON API',
      url: '/api'
    },
    {
      title: 'Favorites',
      url: '/favorites'
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

AudioBrowser.configureBrowser(configuration)

export default function App() {
  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.container}>
        <BatteryWarning />
        <BrowserScreen />
      </SafeAreaView>
    </SafeAreaProvider>
  )
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000000'
  }
})
