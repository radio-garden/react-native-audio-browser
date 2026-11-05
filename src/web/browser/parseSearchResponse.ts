import type { Track } from '../../types'

/**
 * Normalise a search endpoint's HTTP body into `Track[]`. Endpoints return a
 * bare `Track[]`, the only shape iOS and Android decode. A page object
 * `{ children: Track[] }` is also accepted here, on web only.
 */
export function parseSearchResponse(response: unknown): Track[] {
  if (Array.isArray(response)) return response as Track[]
  if (response && typeof response === 'object' && 'children' in response) {
    return (response as { children?: Track[] }).children ?? []
  }
  return []
}
