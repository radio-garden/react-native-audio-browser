import type { Track, ResolvedTrack } from '../../types'
import { trackIdentity } from '../../utils/trackIdentity'

/**
 * Manages favorite state for tracks, keyed by track identity (`id` when set,
 * falling back to `src` — see trackIdentity / ADR 0008).
 * Matches Android's favorite hydration behavior.
 */
export class FavoriteManager {
  private favorites = new Set<string>()

  /**
   * Sets the favorites cache from an array of track identities.
   * This is typically called when favorites are loaded from storage.
   */
  setFavorites(favorites: string[]): void {
    this.favorites = new Set(favorites)
  }

  /**
   * Checks if a track is favorited based on its identity.
   */
  isFavorited(track: Track): boolean {
    const identity = trackIdentity(track)
    return identity !== undefined && this.favorites.has(identity)
  }

  /**
   * Adds a track to the favorites cache under its identity.
   */
  addFavorite(track: Track): void {
    const identity = trackIdentity(track)
    if (identity !== undefined) this.favorites.add(identity)
  }

  /**
   * Removes a track from the favorites cache under its identity.
   */
  removeFavorite(track: Track): void {
    const identity = trackIdentity(track)
    if (identity !== undefined) this.favorites.delete(identity)
  }

  /**
   * Hydrates the favorited property on a track based on the native favorites cache.
   * Matches Android's hydrateFavorite behavior.
   *
   * @param track Track to hydrate
   * @returns Track with favorited property set if found in cache
   */
  hydrateFavorite(track: Track): Track {
    // Don't overwrite API-provided favorites
    if (track.favorited !== undefined && track.favorited !== null) return track
    if (this.favorites.size === 0) return track

    if (!this.isFavorited(track)) return track

    return {
      ...track,
      favorited: true
    }
  }

  /**
   * Hydrates favorites on all children of a ResolvedTrack.
   * Matches Android's hydrateChildren behavior.
   *
   * @param resolvedTrack ResolvedTrack with children to hydrate
   * @returns ResolvedTrack with favorited properties set on children
   */
  hydrateChildren(resolvedTrack: ResolvedTrack): ResolvedTrack {
    const children = resolvedTrack.children
    if (!children) return resolvedTrack

    const hydratedChildren = children.map((track) =>
      this.hydrateFavorite(track)
    )
    return {
      ...resolvedTrack,
      children: hydratedChildren
    }
  }
}
