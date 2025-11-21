import { nativeBrowser } from '../native'

/**
 * Sets the list of favorited track source identifiers.
 *
 * This syncs your app's favorites with the native favorites cache, enabling
 * the heart button in media controllers (notification, Android Auto, CarPlay)
 * to show the correct state.
 *
 * Note: This is only needed if the tracks provided to AudioBrowser do not
 * include the `favorited` field. If your API already includes favorite state in
 * track responses, the native cache is populated automatically during browsing.
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
