import React from 'react';
import AudioBrowser, { setPlayWhenReady } from 'react-native-audio-browser';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { BrowserScreen } from './src/screens/BrowserScreen';

void AudioBrowser.setupPlayer().then(() => setPlayWhenReady(true));

AudioBrowser.configureBrowser({
  tabs: [
    {
      title: 'Music Library',
      url: '/library',
    },
    {
      title: 'Favorites',
      url: '/favorites',
    },
  ],
  routes: {
    '/favorites': {
      url: '/favorites',
      title: 'Favorites',
      children: [
        {
          src: 'https://radio.garden/api/ara/content/listen/EFZafC9V/channel.mp3',
          title: '538 Classics',
        },
      ],
    },
    '/library/radio': {
      url: '/library/radio',
      title: 'Radio Stations',
      children: [
        {
          src: 'https://ais-sa5.cdnstream1.com/b75154_128mp3',
          title: 'Smooth Jazz 24/7',
          artist: 'New York, NY',
          artwork: 'https://rntp.dev/example/smooth-jazz-24-7.jpeg',
        },
        {
          src: 'https://kut.streamguys1.com/kutx-app.aac?listenerId=123456784123',
          title: 'KUTX',
        },
      ],
    },
    '/library': {
      url: '/library',
      title: 'Music Library',
      children: [
        {
          url: '/library/radio',
          title: 'Radio Stations',
        },
        {
          src: 'https://radio.garden/api/ara/content/listen/EFZafC9V/channel.mp3',
          title: '538 Classics',
        },
        {
          src: 'https://rntp.dev/example/Soul%20Searching.mp3',
          title: 'Soul Searching (Demo)',
          artist: 'David Chavez',
          artwork: 'https://rntp.dev/example/Soul%20Searching.jpeg',
          duration: 77,
        },
        {
          src: 'https://rntp.dev/example/Lullaby%20(Demo).mp3',
          title: 'Lullaby (Demo)',
          artist: 'David Chavez',
          artwork: 'https://rntp.dev/example/Lullaby%20(Demo).jpeg',
          duration: 71,
        },
        {
          src: 'https://rntp.dev/example/Rhythm%20City%20(Demo).mp3',
          title: 'Rhythm City (Demo)',
          artist: 'David Chavez',
          artwork: 'https://rntp.dev/example/Rhythm%20City%20(Demo).jpeg',
          duration: 106,
        },
        {
          src: 'https://rntp.dev/example/hls/whip/playlist.m3u8',
          title: 'Whip',
          artist: 'prazkhanal',
          artwork: 'https://rntp.dev/example/hls/whip/whip.jpeg',
        },
        {
          src: 'https://traffic.libsyn.com/atpfm/atp545.mp3',
          title: 'Chapters',
        },
      ],
    },
  },
});

export default function App() {
  return (
    <SafeAreaProvider>
      <BrowserScreen />
    </SafeAreaProvider>
  );
}
