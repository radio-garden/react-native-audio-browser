import React from 'react'
import { StyleSheet } from 'react-native'
import AudioBrowser, {
  BrowserConfiguration,
  setPlayWhenReady,
  Track
} from 'react-native-audio-browser'
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context'
import { BrowserScreen } from './src/screens/BrowserScreen'

void AudioBrowser.setupPlayer().then(() => setPlayWhenReady(true))

const configuration: BrowserConfiguration = {
  play: 'queue',
  tabs: [
    {
      title: 'Music Library',
      url: '/library'
    },
    {
      title: 'Favorites',
      url: '/favorites'
    }
  ],
  routes: {
    '/favorites': {
      url: '/favorites',
      title: 'Favorites',
      children: [
        {
          src: 'https://radio.garden/api/ara/content/listen/EFZafC9V/channel.mp3',
          title: '538 Classics'
        }
      ]
    },
    '/library/radio': {
      url: '/library/radio',
      title: 'Radio Stations',
      children: [
        {
          src: 'https://ais-sa5.cdnstream1.com/b75154_128mp3',
          title: 'Smooth Jazz 24/7',
          artist: 'New York, NY',
          artwork: 'https://rntp.dev/example/smooth-jazz-24-7.jpeg'
        },
        {
          src: 'https://kut.streamguys1.com/kutx-app.aac?listenerId=123456784123',
          title: 'KUTX'
        }
      ]
    },
    '/library': {
      url: '/library',
      title: 'Music Library',
      children: [
        {
          url: '/library/radio',
          title: 'Radio Stations'
        },
        {
          src: 'https://radio.garden/api/ara/content/listen/EFZafC9V/channel.mp3',
          title: '538 Classics'
        },
        {
          src: 'https://rntp.dev/example/Soul%20Searching.mp3',
          title: 'Soul Searching (Demo)',
          artist: 'David Chavez',
          artwork: 'https://rntp.dev/example/Soul%20Searching.jpeg',
          duration: 77
        },
        {
          src: 'https://rntp.dev/example/Lullaby%20(Demo).mp3',
          title: 'Lullaby (Demo)',
          artist: 'David Chavez',
          artwork: 'https://rntp.dev/example/Lullaby%20(Demo).jpeg',
          duration: 71
        },
        {
          src: 'https://rntp.dev/example/Rhythm%20City%20(Demo).mp3',
          title: 'Rhythm City (Demo)',
          artist: 'David Chavez',
          artwork: 'https://rntp.dev/example/Rhythm%20City%20(Demo).jpeg',
          duration: 106
        },
        {
          src: 'https://rntp.dev/example/hls/whip/playlist.m3u8',
          title: 'Whip',
          artist: 'prazkhanal',
          artwork: 'https://rntp.dev/example/hls/whip/whip.jpeg',
        },
        {
          src: 'https://traffic.libsyn.com/atpfm/atp545.mp3',
          title: 'Chapters'
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
    return Promise.resolve(
      Object.values(configuration.routes ?? {}).reduce<Track[]>(
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
    )
  }
}

AudioBrowser.configureBrowser(configuration)

export default function App() {
  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
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
