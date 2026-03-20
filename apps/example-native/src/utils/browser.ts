import { Platform } from 'react-native'
import AudioBrowser, {
  getActiveTrack,
  onTimedMetadata,
  setPlayWhenReady,
  updateNowPlaying,
  type BrowserConfiguration
} from 'react-native-audio-browser'
import {
  archiveLibraryEntry,
  archiveRoutes,
  searchArchive
} from '../api/archive-org'
import {
  radioGardenLibraryEntry,
  radioGardenMediaTransform,
  radioGardenRoutes
} from '../api/radio-garden'
import { fetchFavorites, setupFavorites } from '../favorites'
import { throttle } from './throttle'

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
    transform: radioGardenMediaTransform
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

export const setupBrowser = async () => {
  await AudioBrowser.setupPlayer()
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

  AudioBrowser.configureBrowser(configuration)
}
