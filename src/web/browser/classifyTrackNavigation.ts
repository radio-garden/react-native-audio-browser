import type { Track } from '../../types'
import { BrowserPathHelper } from '../util/BrowserPathHelper'

/**
 * How a selected track should be navigated, decided purely from its shape:
 * - `contextual` — a playable track carrying its parent queue context in a
 *   contextual URL (`{parentPath}?__trackId={src}`); expand/skip within that queue.
 * - `browse` — a browsable track (has a `url` that isn't contextual); drill in.
 * - `playable` — a bare playable track (`src`, no `url`); load and play it alone.
 * - `invalid` — neither `url` nor `src`; nothing to do.
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
  const url = track.url
  if (url && BrowserPathHelper.isContextual(url)) {
    return {
      kind: 'contextual',
      parentPath: BrowserPathHelper.stripTrackId(url),
      trackId: BrowserPathHelper.extractTrackId(url)
    }
  }
  if (url) return { kind: 'browse' }
  if (track.src) return { kind: 'playable' }
  return { kind: 'invalid' }
}
