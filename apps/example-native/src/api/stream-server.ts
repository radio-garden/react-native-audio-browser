import type { Section, Track } from 'react-native-audio-browser'
import { Platform } from 'react-native'

// `corepack yarn stream-server`, on the machine the simulator or emulator runs on.
const BASE = `http://${Platform.OS === 'android' ? '10.0.2.2' : 'localhost'}:8787`

const isLive = (url: string) => new URL(url).pathname === '/live.mp3'

const track = (title: string, path: string): Track => {
  const src = `${BASE}${path}`
  return { title, artist: path, src, live: isLive(src) }
}

export const streamServerSection: Section = {
  title: 'Stream server',
  children: [
    track('File', '/file.mp3'),
    track('Live', '/live.mp3'),
    track('Live, ICY titles', '/live.mp3?icy=5'),
    track('Clean close', '/live.mp3?close=10'),
    track('Silent stall', '/live.mp3?stall=10'),
    track('Short end', '/file.mp3?close=4'),
    track('ICY titles, then a stall', '/live.mp3?icy=5&stall=20'),
    track('503', '/live.mp3?status=503'),
    track('Slow start', '/live.mp3?delay=5'),
    track('Missing Range', '/live.mp3?requireRange')
  ]
}

/** A query that is an http(s) URL, as a track to play; undefined for any other query. */
export function urlTrack(query: string): Track | undefined {
  if (!/^https?:\/\/\S+$/.test(query)) return undefined
  try {
    return { title: query, src: query, live: isLive(query) }
  } catch {
    return undefined
  }
}
