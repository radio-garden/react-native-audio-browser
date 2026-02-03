import React from 'react'
import { StyleSheet } from 'react-native'
import AudioBrowser, {
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
import { BatteryWarning } from 'common-app/src/components/BatteryWarning'
import { throttle } from './src/utils/throttle'
import { BrowserScreen, basicBrowserConfiguration } from 'common-app'

const storage = createMMKV()
let favorites: Track[] = basicBrowserConfiguration._favorites

// Load persisted favorites on startup
const persistedFavorites = storage.getString('favorites')
if (persistedFavorites) {
  favorites = JSON.parse(persistedFavorites) as Track[]
  // Sync with native favorites cache so heart buttons show correct state
  setFavorites(favorites.map((t) => t.src).filter(Boolean) as string[])
}

void AudioBrowser.setupPlayer().then(() => {
  setPlayWhenReady(true)

  // Configure Android notification button layout
  AudioBrowser.updateOptions({
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

    // Update the browser configuration reference so /favorites route returns updated array
    basicBrowserConfiguration._favorites = favorites

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

AudioBrowser.configureBrowser(basicBrowserConfiguration)

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
