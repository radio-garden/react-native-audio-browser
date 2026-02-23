import type { Track, ResolvedTrack } from '../../types'

/**
 * Manages favorite state for tracks.
 * Matches Android's favorite hydration behavior.
 */
export class FavoriteManager {
  private favorites = new Set<string>()

  /**
   * Sets the favorites cache from an array of track src values.
   * This is typically called when favorites are loaded from storage.
   */
  setFavorites(favorites: string[]): void {
    this.favorites = new Set(favorites)
  }

  /**
   * Checks if a track is favorited based on its src.
   */
  isFavorited(src: string): boolean {
    return this.favorites.has(src)
  }

  /**
   * Adds a track src to the favorites cache.
   */
  addFavorite(src: string): void {
    this.favorites.add(src)
  }

  /**
   * Removes a track src from the favorites cache.
   */
  removeFavorite(src: string): void {
    this.favorites.delete(src)
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

    const isFavorited = track.src ? this.favorites.has(track.src) : false
    if (!isFavorited) return track

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
