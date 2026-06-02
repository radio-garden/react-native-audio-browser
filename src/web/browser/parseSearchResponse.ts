import type { Track } from '../../types'

/**
 * Normalise a search endpoint's HTTP body into `Track[]`. Endpoints return the
 * audio-browser page shape `{ children: Track[] }` (matching iOS/Android, which
 * decode a ResolvedTrack and read `.children`). A bare `Track[]` is still
 * accepted for back-compat with callback/array-returning sources.
 */
export function parseSearchResponse(response: unknown): Track[] {
  if (Array.isArray(response)) return response as Track[]
  if (response && typeof response === 'object' && 'children' in response) {
    return (response as { children?: Track[] }).children ?? []
  }
  return []
}
