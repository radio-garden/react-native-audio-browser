import type { ArtworkVariants, TrackArtwork } from '../types/browser-nodes'

/**
 * Whether a track's artwork ships one image per appearance.
 *
 * Narrows the union in one place, so nothing else has to know how it is
 * discriminated.
 */
export function isArtworkVariants(
  artwork: TrackArtwork | undefined
): artwork is ArtworkVariants {
  return typeof artwork === 'object' && artwork !== null
}

/**
 * The single artwork URL to use.
 *
 * Mirrors the Swift `Variant_String_ArtworkVariants.url` accessor: a pair
 * resolves to its dark URL unless a scheme is given, because dark is what a
 * single-URL track would have shipped and is the only appearance Android Auto
 * has. Callers that can render per appearance (CarPlay, via a `UIImageAsset`)
 * take the pair itself rather than going through this.
 */
export function artworkUrl(
  artwork: TrackArtwork | undefined,
  scheme: 'light' | 'dark' = 'dark'
): string | undefined {
  if (artwork === undefined) return undefined
  return isArtworkVariants(artwork) ? artwork[scheme] : artwork
}
