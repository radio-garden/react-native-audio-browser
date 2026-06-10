import { nativeBrowser } from '../../native'
import type { Track } from '../../types/browser-nodes'
import { onActiveTrackChanged } from '../queue/activeTrack'

// MARK: - Types

export interface PlaybackElapsed {
  /** Cumulative seconds spent `playing` since this subscription started. */
  total: number
  /** Seconds played since the previous callback (≈ `period`). */
  sinceLast: number
  /** The currently playing track (id / src / url / title …), if any. */
  track?: Track
}

interface Handle {
  period: number
  totalSeconds: number
  lastFireSeconds: number
  callback: (elapsed: PlaybackElapsed) => void
}

// MARK: - State

const handles = new Set<Handle>()
let installed = false
let currentTrack: Track | undefined

// The native tick fires once per second while playback is `playing` (and stops
// while paused/buffering/stopped), so advancing each handle by one second per
// tick yields cumulative *playback* time that freezes on interruption.
function onTick() {
  for (const handle of handles) {
    handle.totalSeconds += 1
    const sinceLast = handle.totalSeconds - handle.lastFireSeconds
    if (sinceLast >= handle.period) {
      handle.lastFireSeconds = handle.totalSeconds
      handle.callback({ total: handle.totalSeconds, sinceLast, track: currentTrack })
    }
  }
}

function install() {
  if (installed) return
  installed = true
  nativeBrowser.onPlaybackInterval = onTick
  onActiveTrackChanged.addListener((event) => {
    currentTrack = event.track
  })
}

// MARK: - Public API

/**
 * Calls `callback` every `period` seconds of cumulative playback — time spent in
 * the `playing` state. The clock advances only while playing and freezes on
 * pause/buffering/stop, resuming where it left off; it is never reset (cancel and
 * re-subscribe for a fresh `total`). Coarse, best-effort cadence (~1s resolution),
 * not a precise timer.
 *
 * Typical use is a periodic "still listening" signal: fire an analytics ping, a
 * listen-check, or a heartbeat every N seconds of *actual* listening, so paused
 * time doesn't inflate the count.
 *
 * Note this counts cumulative play time across the whole session, NOT per track:
 * `total` keeps climbing through track changes, and `track` only tells you what
 * was playing at the moment each callback fired. To measure playtime per track,
 * subscribe to {@link onActiveTrackChanged} and cancel + re-subscribe here on each
 * change, so every track gets a fresh `total` starting at zero.
 *
 * @param callback Invoked once per elapsed `period`, with cumulative play time
 *                 and the currently playing track.
 * @param period  Seconds of playback between calls. Must be >= 1.
 * @returns A function that cancels this subscription (idempotent — safe to call
 *          more than once).
 */
export function trackPlaybackTime(
  callback: (elapsed: PlaybackElapsed) => void,
  period: number
): () => void {
  if (!(period >= 1)) {
    throw new Error('trackPlaybackTime: period must be >= 1')
  }
  install()
  const handle: Handle = {
    period,
    totalSeconds: 0,
    lastFireSeconds: 0,
    callback
  }
  handles.add(handle)
  if (handles.size === 1) nativeBrowser.setPlaybackIntervalEnabled(true)
  return () => {
    if (!handles.delete(handle)) return
    if (handles.size === 0) nativeBrowser.setPlaybackIntervalEnabled(false)
  }
}

/** @internal test hook — drives the fan-out without a native tick. */
export function __emitTickForTests(ticks = 1) {
  for (let i = 0; i < ticks; i++) onTick()
}
