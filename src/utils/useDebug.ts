import { useEffect, useRef } from 'react'
import { useContent, usePath } from '../features/browser'
import { useNavigationError, usePlaybackError } from '../features/errors'
import {
  onMetadataChapterReceived,
  onMetadataCommonReceived,
  onMetadataTimedReceived,
  onPlaybackMetadata,
} from '../features/metadata'
import { useOnline } from '../features/network'
import { useNowPlaying } from '../features/nowPlaying'
import { usePlayingState } from '../features/playback/playing'
import { usePlayWhenReady } from '../features/playback/playWhenReady'
import { useProgress } from '../features/playback/progress'
import { usePlayback } from '../features/playback/state'
import { useOptions } from '../features/player/options'
import { useActiveTrack } from '../features/queue/activeTrack'
import { useQueue } from '../features/queue/queue'
import { useRepeatMode } from '../features/queue/repeatMode'

/**
 * Debug hook that logs all audio browser state changes.
 * Drop this into any component to see what's happening.
 *
 * @param enabled - Whether logging is enabled (default: __DEV__)
 *
 * @example
 * ```tsx
 * function MyScreen() {
 *   useDebug()
 *   // ... rest of component
 * }
 * ```
 */
export function useDebug(enabled: boolean = __DEV__): void {
  const activeTrack = useActiveTrack()
  const playback = usePlayback()
  const playingState = usePlayingState()
  const playWhenReady = usePlayWhenReady()
  const progress = useProgress()
  const queue = useQueue()
  const repeatMode = useRepeatMode()
  const options = useOptions()
  const playbackError = usePlaybackError()
  const navigationError = useNavigationError()
  const online = useOnline()
  const nowPlaying = useNowPlaying()
  const path = usePath()
  const content = useContent()

  const prevRef = useRef<Record<string, unknown>>({})
  const lastChangeRef = useRef(Date.now())
  const isFirstRender = useRef(true)

  const values: Record<string, unknown> = {
    'activeTrack': activeTrack,
    'state': playback.state,
    'playingState': playingState,
    'playWhenReady': playWhenReady,
    'position': Math.round(progress.position),
    'duration': Math.round(progress.duration),
    'queue': queue.map((t) => t.src).join(', '),
    'repeatMode': repeatMode,
    'options': options,
    'online': online,
    'nowPlaying': nowPlaying,
    'playbackError': playbackError?.message ?? null,
    'navigationError': navigationError?.message ?? null,
    'path': path ?? null,
    'content': content?.title ?? null,
  }

  if (!enabled) return

  const now = Date.now()
  const elapsed = now - lastChangeRef.current
  const prev = prevRef.current
  const changes: string[] = []

  for (const key of Object.keys(values)) {
    // Skip small position changes to reduce noise
    if (key === 'position') {
      const prevPos = prev[key] as number | undefined
      const currPos = values[key] as number
      if (prevPos !== undefined && Math.abs(prevPos - currPos) < 2) {
        continue
      }
    }
    if (!Object.is(prev[key], values[key])) {
      changes.push(`${key}:
    old → ${formatIndented(prev[key])}
    new → ${formatIndented(values[key])}\n`)
    }
  }

  if (isFirstRender.current) {
    console.log(`[useDebug] initial state:`)
    for (const [k, v] of Object.entries(values)) {
      console.log(`  ${k}: ${format(v)}`)
    }
    isFirstRender.current = false
    lastChangeRef.current = now
  } else if (changes.length > 0) {
    console.log(`[useDebug] (+${elapsed}ms)\n  ${changes.join('\n  ')}`)
    lastChangeRef.current = now
  }

  prevRef.current = { ...values }
}

function format(value: unknown): string {
  if (value === undefined) return 'undefined'
  if (value === null) return 'null'
  if (typeof value === 'string') return `"${value}"`
  if (Array.isArray(value)) return `[${value.map((v) => format(v)).join(', ')}]`
  if (typeof value === 'object') return JSON.stringify(value, null, 2)
  // eslint-disable-next-line @typescript-eslint/no-base-to-string
  return String(value)
}

function indent(str: string, spaces: number): string {
  const pad = ' '.repeat(spaces)
  return str
    .split('\n')
    .map((line, index) => index === 0 ? line : pad + line)
    .join('\n')
}

function formatIndented(value: unknown): string {
  return indent(format(value), 4)
}

/**
 * Debug hook that logs stream metadata events.
 * Use this when debugging live streams with ICY/ID3 metadata.
 *
 * @param enabled - Whether logging is enabled (default: __DEV__)
 *
 * @example
 * ```tsx
 * function MyScreen() {
 *   useDebugMetadata()
 *   // ... rest of component
 * }
 * ```
 */
export function useDebugMetadata(enabled: boolean = __DEV__): void {
  const mountTimeRef = useRef(Date.now())

  useEffect(() => {
    if (!enabled) return

    const elapsed = () => Date.now() - mountTimeRef.current

    const unsubscribeCommon = onMetadataCommonReceived.addListener((event) => {
      const { metadata } = event
      const fields = Object.entries(metadata)
        .filter(([, v]) => v != null)
        .map(([k, v]) => `${k}: "${v}"`)
      console.log(`[useDebugMetadata] common (+${elapsed()}ms)\n  ${fields.join('\n  ')}`)
    })

    const unsubscribePlayback = onPlaybackMetadata.addListener((metadata) => {
      const fields = Object.entries(metadata)
        .filter(([, v]) => v != null)
        .map(([k, v]) => `${k}: "${v}"`)
      console.log(`[useDebugMetadata] playback (+${elapsed()}ms)\n  ${fields.join('\n  ')}`)
    })

    const unsubscribeTimed = onMetadataTimedReceived.addListener((event) => {
      console.log(`[useDebugMetadata] timed (+${elapsed()}ms): ${event.metadata.length} entries`)
    })

    const unsubscribeChapter = onMetadataChapterReceived.addListener((event) => {
      console.log(`[useDebugMetadata] chapter (+${elapsed()}ms): ${event.metadata.length} entries`)
    })

    return () => {
      unsubscribeCommon()
      unsubscribePlayback()
      unsubscribeTimed()
      unsubscribeChapter()
    }
  }, [enabled])
}
