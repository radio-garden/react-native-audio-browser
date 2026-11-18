import type { Track } from '../../types'
import { BrowserPathHelper } from '../util/BrowserPathHelper'

/**
 * How a selected track should be navigated, decided purely from its shape:
 * - `contextual` — a playable track carrying its parent queue context in a
 *   contextual path (`{parentPath}?__trackId={src}`); expand/skip within that queue.
 * - `browse` — a browsable track (has a `path` that isn't contextual); drill in.
 * - `playable` — a bare playable track (`src`, no `path`); load and play it alone.
 * - `invalid` — neither `path` nor `src`; nothing to do.
 */
export type TrackNavigation =
  | { kind: 'contextual'; parentPath: string; trackId: string | undefined }
  | { kind: 'browse' }
  | { kind: 'playable' }
  | { kind: 'invalid' }

/**
 * Pure classifier for {@link Track} navigation — the web analog of iOS's
 * TrackSelector decision tree. Executing the chosen action stays with the
 * caller; this only decides which action applies.
 */
export function classifyTrackNavigation(track: Track): TrackNavigation {
  const path = track.path
  if (path && BrowserPathHelper.isContextual(path)) {
    return {
      kind: 'contextual',
      parentPath: BrowserPathHelper.stripTrackId(path),
      trackId: BrowserPathHelper.extractTrackId(path)
    }
  }
  if (path) return { kind: 'browse' }
  if (track.src) return { kind: 'playable' }
  return { kind: 'invalid' }
}
