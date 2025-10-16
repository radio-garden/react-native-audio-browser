import { NitroModules } from 'react-native-nitro-modules'
import type { AudioBrowser as AudioBrowserSpec } from './specs/audio-browser.nitro'

export const AudioBrowser =
  NitroModules.createHybridObject<AudioBrowserSpec>('AudioBrowser')

// // Export types
// export type { AudioBrowser } from './specs/audio-browser.nitro'
// export type {
//   PlayerContentType,
//   PlayerConfig,
//   MediaSessionConfig,
//   PlaybackError,
//   PlaybackState
// } from '../types/player'

// // Simple: Static + Callback Content
// function getRecentlyPlayed(): Promise<BrowserTrack[]> {
//   return Promise.resolve([])
// }
// function getToken(): string {
//   return `${Date.now()}`
// }

// const tracks: BrowserTrack[] = []

// const staticConfigSimple = {
//   tabs: [
//     { url: '/favorites', title: 'Favorites' },
//     { url: '/recent', title: 'Recent' },
//   ],
//   routes: {
//     '/favorites': {
//       url: '/favorites',
//       title: 'My Favorites',
//       children: [
//         { url: '/track1', title: 'Song 1', src: 'https://music.com/song1.mp3' },
//         { url: '/track2', title: 'Song 2', src: 'https://music.com/song2.mp3' },
//       ],
//     },
//     '/recent': async () => ({
//       title: 'Recently Played',
//       children: await getRecentlyPlayed(),
//     }),
//   },
//   search: (query) =>
//     Promise.resolve(tracks.filter((t) => t.title.includes(query))),
// } satisfies Config

// // Medium: Single API with Defaults

// const apiConfigSimple = {
//   request: {
//     baseUrl: 'https://radio.garden/api/ara/content',
//     userAgent: 'MyRadioApp/1.0',
//     query: { s: 1, hl: 'en' },
//   },
//   search: {
//     baseUrl: 'https://radio.garden/api/search',
//   },
//   // tabs: uses request config, loads from https://radio.garden/api/ara/content/?s=1&hl=en
//   // browse: uses request config, /page/123 → https://radio.garden/api/ara/content/page/123?s=1&hl=en
// } satisfies Config

// export const apiConfigComplex = {
//   request: {
//     userAgent: 'MyMusicApp/1.0',
//   },
//   tabs: [
//     { url: '?section=music', title: 'Music' },
//     { url: '?section=podcasts', title: 'Podcasts' },
//     { url: '?section=live', title: 'Live Radio' },
//   ],
//   routes: {
//     '/content': {
//       baseUrl: 'https://api.music.com/content',
//       transform(request) {
//         return {
//           ...request,
//           headers: { Authorization: `Bearer ${getToken()}` },
//         }
//       },
//     },
//     '/live': {
//       baseUrl: 'https://radio-api.music.com',
//       transform(request) {
//         return {
//           ...request,
//           headers: { 'X-Stream-Quality': 'high' },
//         }
//       },
//     },
//   },
//   search: {
//     baseUrl: 'https://search-api.music.com',
//     transform(request) {
//       return {
//         ...request,
//         headers: { Authorization: `Bearer ${getToken()}` },
//         query: { ...request.query, limit: 50 },
//       }
//     },
//   },
//   media: {
//     baseUrl: 'https://cdn.music.com',
//     transform(request) {
//       return {
//         ...request,
//         headers: { Authorization: `Bearer ${getToken()}` },
//         path: request.path?.replace('/stream/', '/hls/'),
//       }
//     },
//   },
// } satisfies Config

// export const a = [staticConfigSimple, apiConfigSimple, apiConfigComplex]
