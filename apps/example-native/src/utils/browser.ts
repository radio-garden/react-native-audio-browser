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
      path: '/library',
      artwork: Platform.select({ ios: 'sf:music.note.list' })
    },
    {
      title: 'JSON API',
      path: '/api',
      artwork: Platform.select({ ios: 'sf:server.rack' })
    },
    {
      title: 'Favorites',
      path: '/favorites',
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
      path: '/library',
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
    // Enable favoriting (now-playing heart + browse-row hearts). Favorites are
    // stored as full `src` strings here, so 'exact' matching (`true`) fits.
    // jumpForward/jumpBackward default to off, and an explicit layout is
    // filtered by capability - so the jump buttons below need these on or they
    // are silently dropped from the layout.
    capabilities: { favorite: true, jumpForward: true, jumpBackward: true },
    android: {
      // Jump either side of play/pause. Claiming back/forward is what makes
      // Media3 tell the car to stop reserving space for skip-prev/next.
      // Overflow is priority-ordered: a head unit with a spare slot promotes
      // the first entry onto the main row.
      remoteButtonLayout: {
        back: 'jump-backward',
        forward: 'jump-forward',
        overflow: ['favorite']
      }
    },
    ios: {
      carPlayNowPlayingButtons: ['favorite', 'repeat', 'playback-rate']
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
