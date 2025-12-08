import { useRef } from 'react'
import { useContent, usePath } from '../features/browser'
import { useNavigationError, usePlaybackError } from '../features/errors'
import { usePlayingState } from '../features/playback/playing'
import { usePlayWhenReady } from '../features/playback/playWhenReady'
import { useProgress } from '../features/playback/progress'
import { usePlayback } from '../features/playback/state'
import { useActiveTrack } from '../features/queue/activeTrack'
import { useQueue } from '../features/queue/queue'

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
  const playbackError = usePlaybackError()
  const navigationError = useNavigationError()
  const path = usePath()
  const content = useContent()

  const prevRef = useRef<Record<string, unknown>>({})
  const mountTimeRef = useRef(Date.now())
  const isFirstRender = useRef(true)

  const values: Record<string, unknown> = {
    'track': activeTrack?.title ?? null,
    'track.src': activeTrack?.src ?? null,
    'state': playback.state,
    'playing': playingState.playing,
    'buffering': playingState.buffering,
    'playWhenReady': playWhenReady,
    'position': Math.round(progress.position),
    'duration': Math.round(progress.duration),
    'queueLength': queue.length,
    'playbackError': playbackError?.message ?? null,
    'navigationError': navigationError?.message ?? null,
    'path': path ?? null,
    'content': content?.title ?? null,
  }

  if (!enabled) return

  const elapsed = Date.now() - mountTimeRef.current
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
      changes.push(`${key}: ${format(prev[key])} → ${format(values[key])}`)
    }
  }

  if (isFirstRender.current) {
    console.log(`[useDebug] initial state (+${elapsed}ms):`)
    for (const [k, v] of Object.entries(values)) {
      console.log(`  ${k}: ${format(v)}`)
    }
    isFirstRender.current = false
  } else if (changes.length > 0) {
    console.log(`[useDebug] (+${elapsed}ms)\n  ${changes.join('\n  ')}`)
  }

  prevRef.current = { ...values }
}

function format(value: unknown): string {
  if (value === undefined) return 'undefined'
  if (value === null) return 'null'
  if (typeof value === 'string') return `"${value}"`
  return String(value)
}
