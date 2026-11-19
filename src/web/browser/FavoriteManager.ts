import type { Track, ResolvedTrack } from '../../types'
import { getTrackIdentity } from '../../utils/getTrackIdentity'

/**
 * Manages favorite state for tracks, keyed by track identity (`id` when set,
 * falling back to `src` — see getTrackIdentity / ADR 0008).
 * Matches Android's favorite hydration behavior.
 */
export class FavoriteManager {
  private favorites = new Set<string>()

  /**
   * Sets the favorites cache from an array of track identities.
   * This is typically called when favorites are loaded from storage.
   *
   * @returns the identities whose favorite state changed
   */
  setFavorites(favorites: string[]): Set<string> {
    const replaced = new Set(favorites)
    const changed = new Set<string>()
    for (const identity of replaced) {
      if (!this.favorites.has(identity)) changed.add(identity)
    }
    for (const identity of this.favorites) {
      if (!replaced.has(identity)) changed.add(identity)
    }
    this.favorites = replaced
    return changed
  }

  /**
   * Checks if a track is favorited based on its identity.
   */
  isFavorited(track: Track): boolean {
    const identity = getTrackIdentity(track)
    return identity !== undefined && this.favorites.has(identity)
  }

  /**
   * Adds a track to the favorites cache under its identity.
   */
  addFavorite(track: Track): void {
    const identity = getTrackIdentity(track)
    if (identity !== undefined) this.favorites.add(identity)
  }

  /**
   * Removes a track from the favorites cache under its identity.
   */
  removeFavorite(track: Track): void {
    const identity = getTrackIdentity(track)
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
   * `track` with `favorited` as the cache has it, for a track whose identity
   * is in `changed` (what `setFavorites` returned). Unlike `hydrateFavorite`,
   * this overwrites the value the track carries. Undefined when the track
   * stands; an unset flag counts as not favorited.
   */
  restamp(track: Track, changed: Set<string>): Track | undefined {
    const identity = getTrackIdentity(track)
    if (identity === undefined || !changed.has(identity)) return undefined
    const favorited = this.favorites.has(identity)
    if ((track.favorited === true) === favorited) return undefined
    return { ...track, favorited }
  }

  /**
   * Hydrates favorites on all tracks of a resolved page.
   * Matches Android's hydrateChildren behavior. Pages reach hydration
   * normalized (ADR 0010), so `sections` is the only structure to walk.
   *
   * @param resolvedTrack Resolved page to hydrate
   * @returns Resolved page with favorited properties set on its tracks
   */
  hydrateChildren(resolvedTrack: ResolvedTrack): ResolvedTrack {
    const { sections } = resolvedTrack
    if (!sections) return resolvedTrack

    return {
      ...resolvedTrack,
      sections: sections.map((section) => ({
        ...section,
        children: section.children.map((track) => this.hydrateFavorite(track))
      }))
    }
  }
}
