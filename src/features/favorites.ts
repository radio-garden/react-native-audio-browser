import { nativeBrowser } from '../native'
import type { Track } from '../types'
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
 * Sets the list of favorited track source identifiers.
 *
 * This syncs your app's favorites with the native favorites cache, enabling
 * the heart button in media controllers (notification, Android Auto, CarPlay)
 * to show the correct state.
 *
 * Note: This is only needed if the tracks provided to AudioBrowser do not
 * include the `favorited` field. If your API already includes favorite state in
 * track responses, the native favorites cache is populated automatically during browsing.
 *
 * When the heart button is tapped in media controllers or when you call
 * `setActiveTrackFavorited()`, the native favorites cache is automatically
 * updated. You only need to call this on app launch to hydrate the cache.
 *
 * @param favorites - Array of favorited track `src` values
 *
 * @example
 * ```ts
 * const favoriteSrcs = await loadFavoritesFromStorage()
 * setFavorites(favoriteSrcs)
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
 * @returns Cleanup function to unsubscribe
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
