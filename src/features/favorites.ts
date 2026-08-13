import type { Track } from '../types'
import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'

// MARK: - Setters

/**
 * Sets the favorited state of the currently playing track.
 * Updates the heart icon in media controllers (notification, Android Auto).
 *
 * Use this for programmatic favorite changes (e.g., from a favorite button in your app).
 * For heart button taps from media controllers, use `onFavoriteChanged` instead -
 * the native side handles those automatically.
 *
 * @param favorited - Whether the track is favorited
 *
 * @example
 * ```ts
 * setActiveTrackFavorited(true)
 * ```
 */
export function setActiveTrackFavorited(favorited: boolean): void {
  nativeBrowser.setActiveTrackFavorited(favorited)
}

/**
 * Toggles the favorited state of the currently playing track.
 *
 * @example
 * ```ts
 * // In a button handler
 * toggleActiveTrackFavorited()
 * ```
 */
export function toggleActiveTrackFavorited(): void {
  nativeBrowser.toggleActiveTrackFavorited()
}

/**
 * Sets the list of favorited track identifiers.
 *
 * This syncs your app's favorites with the native favorites cache, enabling
 * the heart button in media controllers (notification, Android Auto, CarPlay)
 * to show the correct state.
 *
 * Each identifier is matched **exactly** against a track's *identity* — its
 * `id` when set, falling back to `src`. Store favorites as the same stable
 * identifier you put in `Track.id` (or as the full `src` for id-less tracks).
 *
 * A track whose response already carries `favorited` keeps that value on
 * display surfaces — a caller-set flag wins over cache hydration. The cache
 * itself is written only by this call and by heart toggles
 * (`setActiveTrackFavorited()` / media-controller taps), so you only need to
 * call this on app launch — and again whenever the collection changes outside
 * the player (a sync from another device, say).
 *
 * @param favorites - Array of favorited track identities (`id`, or `src` for id-less tracks)
 *
 * @example
 * ```ts
 * const favoriteIds = await loadFavoritesFromStorage()
 * setFavorites(favoriteIds)
 * ```
 */
export function setFavorites(favorites: string[]): void {
  nativeBrowser.setFavorites(favorites)
}

// MARK: - Event Callbacks

/**
 * Event data for when the favorite state of the active track changes.
 * Emitted when the user taps the heart button in a media controller (notification, Android Auto, CarPlay).
 */
export interface FavoriteChangedEvent {
  /** The track whose favorite state changed. */
  track: Track
  /** The new favorite state. */
  favorited: boolean
}

/**
 * Subscribes to favorite state change events.
 * Called when the user taps the heart button in a media controller.
 * The native side has already updated the track's favorite state and UI.
 *
 * @param callback - Called with the track and its new favorite state
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 *
 * @example
 * ```ts
 * const unsubscribe = onFavoriteChanged.addListener(({ track, favorited }) => {
 *   // Persist the change to your backend/storage
 *   if (favorited) {
 *     addToFavorites(track)
 *   } else {
 *     removeFromFavorites(track)
 *   }
 * })
 * ```
 */
export const onFavoriteChanged =
  NativeUpdatedValue.emitterize<FavoriteChangedEvent>(
    (cb) => (nativeBrowser.onFavoriteChanged = cb)
  )
