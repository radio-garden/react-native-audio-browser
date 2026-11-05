import type { Track } from '../types'
import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'

// MARK: - Setters

/**
 * Sets the favorited state of the Active Track.
 * Updates the heart icon in media controllers (notification, Android Auto, CarPlay).
 *
 * Use this for favorite changes from your app, such as a favorite button.
 * It emits `onFavoriteChanged`, so persist the change there, not again in your
 * button handler.
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
 * Toggles the favorited state of the Active Track. Emits `onFavoriteChanged`.
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
 * Event data for when the favorite state of the Active Track changes.
 * Emitted on every change: a heart tap in a media controller (notification,
 * Android Auto, CarPlay), a Siri request (iOS), `setActiveTrackFavorited` or
 * `toggleActiveTrackFavorited`.
 */
export interface FavoriteChangedEvent {
  /** The track whose favorite state changed. */
  track: Track
  /** The new favorite state. */
  favorited: boolean
}

/**
 * Subscribes to favorite changes of the Active Track, with the track and its
 * new favorite state. Fires on every change: a heart tap in a media controller,
 * a Siri request (iOS), `setActiveTrackFavorited` or
 * `toggleActiveTrackFavorited`. Persist favorites here, in one place. The
 * native side has already updated the track's favorite state and UI.
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
