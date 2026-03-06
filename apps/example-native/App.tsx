import React from 'react'
import { Platform, StyleSheet } from 'react-native'
import AudioBrowser, {
  BrowserConfiguration,
  getActiveTrack,
  onTimedMetadata,
  setPlayWhenReady,
  updateNowPlaying
} from 'react-native-audio-browser'
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context'
import {
  archiveLibraryEntry,
  archiveRoutes,
  searchArchive,
} from './src/api/archive-org'
import {
  radioGardenLibraryEntry,
  radioGardenMediaTransform,
  radioGardenRoutes,
} from './src/api/radio-garden'
import { BatteryWarning } from './src/components/BatteryWarning'
import { fetchFavorites, setupFavorites } from './src/favorites'
import { BrowserScreen } from './src/screens/BrowserScreen'
import { throttle } from './src/utils/throttle'

void AudioBrowser.setupPlayer().then(() => {
  setPlayWhenReady(true)

  AudioBrowser.updateOptions({
    android: {
      notificationButtons: {
        back: 'skip-to-previous',
        forward: 'skip-to-next',
        overflow: ['favorite']
      }
    }
  })

  setupFavorites()

  // Update now playing from stream metadata (ICY/ID3 tags from live radio)
  const updateNowPlayingThrottled = throttle(updateNowPlaying, 4000, {
    leading: false,
    trailing: true
  })

  onTimedMetadata.addListener((metadata) => {
    const track = getActiveTrack()
    const artistLine = [metadata.title, metadata.artist]
      .filter(Boolean)
      .join(' - ')

    if (artistLine) {
      updateNowPlayingThrottled({
        title: track?.title,
        artist: artistLine
      })
    } else {
      updateNowPlaying(null)
    }
  })
})

const configuration: BrowserConfiguration = {
  tabs: [
    {
      title: 'Library',
      url: '/library',
      artwork: Platform.select({ ios: 'sf:music.note.list' })
    },
    {
      title: 'JSON API',
      url: '/api',
      artwork: Platform.select({ ios: 'sf:server.rack' })
    },
    {
      title: 'Favorites',
      url: '/favorites',
      artwork: Platform.select({ ios: 'sf:heart.fill' })
    }
  ],
  media: {
    transform: radioGardenMediaTransform,
  },
  routes: {
    '/api/**': { baseUrl: 'http://localhost:3003' },
    '/favorites': fetchFavorites,
    ...radioGardenRoutes,
    ...archiveRoutes,
    '/library': {
      url: '/library',
      title: 'Library',
      carPlaySiriListButton: 'top',
      children: [
        archiveLibraryEntry,
        radioGardenLibraryEntry,
        {
          src: 'https://traffic.libsyn.com/atpfm/atp545.mp3',
          title: 'Chapters',
          groupTitle: 'Other'
        }
      ]
    }
  },

  async search({ query }) {
    return searchArchive(query)
  },
  carPlayNowPlayingButtons: ['favorite', 'repeat', 'playback-rate'],

  formatNavigationError({ error, defaultFormatted, path }) {
    if (error.code === 'network-error' && path.startsWith('/api')) {
      return {
        title: 'Api Example Server Not Running',
        message: 'Start the local server with: yarn api-server'
      }
    }
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
