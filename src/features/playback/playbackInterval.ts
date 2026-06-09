import { nativeBrowser } from '../../native'

interface Handle {
  everyMs: number
  sinceMs: number
  callback: () => void
}

const handles = new Set<Handle>()
let installed = false

function onTick(deltaMs: number) {
  for (const h of handles) {
    h.sinceMs += deltaMs
    while (h.sinceMs >= h.everyMs) {
      h.sinceMs -= h.everyMs
      h.callback()
    }
  }
}

function install() {
  if (installed) return
  installed = true
  nativeBrowser.onPlaybackInterval = () => onTick(1000)
}

/**
 * Calls `callback` every `everyMs` while playback is `playing`. The clock only
 * advances while playing (no ticks while paused/stopped). Best-effort cadence
 * (≤1s jitter), not a precise playback-time clock. Returns a function that
 * cancels this subscription.
 */
export function setPlaybackInterval(
  callback: () => void,
  everyMs: number
): () => void {
  if (!(everyMs >= 1)) {
    throw new Error('setPlaybackInterval: everyMs must be >= 1')
  }
  install()
  const handle: Handle = { everyMs, sinceMs: 0, callback }
  handles.add(handle)
  if (handles.size === 1) nativeBrowser.setPlaybackIntervalEnabled(true)
  return () => {
    if (!handles.delete(handle)) return
    if (handles.size === 0) nativeBrowser.setPlaybackIntervalEnabled(false)
  }
}

/** @internal test hook — drives the fan-out without a native tick. */
export function __emitTickForTests(deltaMs: number) {
  onTick(deltaMs)
}
