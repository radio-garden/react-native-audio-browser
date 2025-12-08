import { useEffect, useRef, useSyncExternalStore } from 'react'
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
import type { PlayingState } from '../features/playback/playing'
import { usePlayingState } from '../features/playback/playing'
import { usePlayWhenReady } from '../features/playback/playWhenReady'
import { useProgress } from '../features/playback/progress'
import type { PlaybackState } from '../features/playback/state'
import { usePlayback } from '../features/playback/state'
import type { Options } from '../features/player/options'
import { useOptions } from '../features/player/options'
import { useActiveTrack } from '../features/queue/activeTrack'
import { useQueue } from '../features/queue/queue'
import type { RepeatMode } from '../features/queue/repeatMode'
import { useRepeatMode } from '../features/queue/repeatMode'
import type { NowPlayingMetadata } from '../features/metadata'
import type { Track } from '../types'

// MARK: - Types

interface DebugLogEntry {
  timestamp: number
  elapsed: number | null
  type: 'initial' | 'change' | 'metadata'
  message: string
}

interface DebugState {
  activeTrack: Track | undefined
  state: PlaybackState
  playingState: PlayingState
  playWhenReady: boolean
  position: number
  duration: number
  queue: string
  repeatMode: RepeatMode
  options: Options
  online: boolean
  nowPlaying: NowPlayingMetadata | undefined
  playbackError: string | null
  navigationError: string | null
  path: string | null
  content: string | null
}

interface DebugOptions {
  /** Whether logging is enabled (default: __DEV__) */
  enabled?: boolean
  /** Whether to log stream metadata events (default: false) */
  metadata?: boolean
}

// MARK: - Log Store

const MAX_LOG_ENTRIES = 100
let debugLogs: DebugLogEntry[] = []
const debugLogListeners = new Set<() => void>()

function addDebugLog(entry: DebugLogEntry) {
  debugLogs = [...debugLogs.slice(-(MAX_LOG_ENTRIES - 1)), entry]
  debugLogListeners.forEach((listener) => listener())
}

function clearDebugLogs() {
  debugLogs = []
  debugLogListeners.forEach((listener) => listener())
}

function subscribeToDebugLogs(listener: () => void) {
  debugLogListeners.add(listener)
  return () => debugLogListeners.delete(listener)
}

function getDebugLogs() {
  return debugLogs
}

// MARK: - State Hook

function useDebugState(): DebugState {
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

  return {
    activeTrack,
    state: playback.state,
    playingState,
    playWhenReady,
    position: Math.round(progress.position),
    duration: Math.round(progress.duration),
    queue: queue.map((t) => t.src).join(', '),
    repeatMode,
    options,
    online,
    nowPlaying,
    playbackError: playbackError?.message ?? null,
    navigationError: navigationError?.message ?? null,
    path: path ?? null,
    content: content?.title ?? null,
  }
}

// MARK: - Main Hook

/**
 * Debug hook that provides state inspection and change logging.
 *
 * @param options - Configuration options
 * @returns Debug object with state, logs, and clear function
 *
 * @example
 * ```tsx
 * function MyScreen() {
 *   const debug = useDebug()
 *   // debug.state - current state
 *   // debug.logs - log history
 *   // debug.clear() - clear logs
 * }
 *
 * // With metadata logging for live streams
 * function RadioScreen() {
 *   const debug = useDebug({ metadata: true })
 * }
 * ```
 */
export function useDebug(options: DebugOptions = {}) {
  const { enabled = __DEV__, metadata = false } = options

  const state = useDebugState()
  const logs = useSyncExternalStore(subscribeToDebugLogs, getDebugLogs, getDebugLogs)

  const prevRef = useRef<DebugState | null>(null)
  const lastChangeRef = useRef(Date.now())
  const isFirstRender = useRef(true)

  // State change logging
  useEffect(() => {
    if (!enabled) return

    const now = Date.now()
    const elapsed = now - lastChangeRef.current
    const prev = prevRef.current
    const changes: string[] = []

    for (const key of Object.keys(state) as (keyof DebugState)[]) {
      // Skip small position changes to reduce noise
      if (key === 'position') {
        const prevPos = prev?.[key]
        const currPos = state[key]
        if (prevPos !== undefined && Math.abs(prevPos - currPos) < 2) {
          continue
        }
      }
      if (!Object.is(prev?.[key], state[key])) {
        changes.push(`${key}:
    old → ${formatIndented(prev?.[key])}
    new → ${formatIndented(state[key])}\n`)
      }
    }

    if (isFirstRender.current) {
      const message = Object.entries(state)
        .map(([k, v]) => `${k}: ${format(v)}`)
        .join('\n')
      console.log(`[useDebug] initial state:\n  ${message.split('\n').join('\n  ')}`)
      addDebugLog({ timestamp: now, elapsed: null, type: 'initial', message })
      isFirstRender.current = false
      lastChangeRef.current = now
    } else if (changes.length > 0) {
      const message = changes.join('\n')
      console.log(`[useDebug] (+${elapsed}ms)\n  ${message.split('\n').join('\n  ')}`)
      addDebugLog({ timestamp: now, elapsed, type: 'change', message })
      lastChangeRef.current = now
    }

    prevRef.current = state
  })

  // Metadata logging
  useEffect(() => {
    if (!enabled || !metadata) return

    const logMetadata = (type: string, message: string) => {
      const now = Date.now()
      const elapsed = now - lastChangeRef.current
      console.log(`[useDebug:metadata] ${type} (+${elapsed}ms)\n  ${message.split('\n').join('\n  ')}`)
      addDebugLog({ timestamp: now, elapsed, type: 'metadata', message: `[${type}] ${message}` })
      lastChangeRef.current = now
    }

    const unsubscribeCommon = onMetadataCommonReceived.addListener((event) => {
      const { metadata } = event
      const fields = Object.entries(metadata)
        .filter(([, v]) => v != null)
        .map(([k, v]) => `${k}: "${v}"`)
      logMetadata('common', fields.join('\n'))
    })

    const unsubscribePlayback = onPlaybackMetadata.addListener((metadata) => {
      const fields = Object.entries(metadata)
        .filter(([, v]) => v != null)
        .map(([k, v]) => `${k}: "${v}"`)
      logMetadata('playback', fields.join('\n'))
    })

    const unsubscribeTimed = onMetadataTimedReceived.addListener((event) => {
      logMetadata('timed', `${event.metadata.length} entries`)
    })

    const unsubscribeChapter = onMetadataChapterReceived.addListener((event) => {
      logMetadata('chapter', `${event.metadata.length} entries`)
    })

    return () => {
      unsubscribeCommon()
      unsubscribePlayback()
      unsubscribeTimed()
      unsubscribeChapter()
    }
  }, [enabled, metadata])

  return {
    state,
    logs,
    clear: clearDebugLogs,
  }
}

// MARK: - Formatters

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
    .map((line, index) => (index === 0 ? line : pad + line))
    .join('\n')
}

function formatIndented(value: unknown): string {
  return indent(format(value), 4)
}
