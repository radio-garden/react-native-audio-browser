import type { BrowserConfiguration, ResolvedTrack, Track } from '../types'
import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'
import {
  SEARCH_ROUTE_PATH,
  toNativeConfig,
  validateBrowserConfiguration
} from './browser-config'

let currentConfiguration: BrowserConfiguration | undefined

/**
 * Configures the browser with routes, tabs, and other settings.
 * Also registers the browser with the player to enable:
 * - Media URL transformation for authenticated playback
 * - Android Auto / CarPlay browsing integration
 * - Playback of browsable tracks via navigate()
 *
 * Calling this again REPLACES the entire configuration (no merging) and
 * re-runs initial navigation — external browse UIs such as CarPlay reset to
 * the first tab. For values that change at runtime (base URL, locale, auth
 * token), don't reconfigure: read them inside a `request`/`browse` resolver
 * or a `transform`, and call {@link invalidateAllContent} so every surface
 * re-fetches in place.
 *
 * @param configuration - Browser configuration including routes, tabs, media config, etc.
 *
 * @example
 * ```ts
 * configureBrowser({
 *   request: { baseUrl: 'https://api.example.com' },
 *   routes: {
 *     // `{id}` is a route parameter — see the `routes` docs for matching rules.
 *     '/albums/{id}': async ({ routeParams }) => fetchAlbumPage(routeParams?.id),
 *     // Remapping a browse path to a different API path requires a transform;
 *     // a static `path` on a request config does NOT rewrite the path.
 *     '/artists/{id}': {
 *       transformSync: (request, routeParams) => ({
 *         ...request,
 *         path: `/api/v2/artists/${routeParams?.id}`
 *       })
 *     }
 *   },
 *   tabs: [
 *     { title: 'Home', path: '/' },
 *     { title: 'Search', path: '/search' }
 *   ]
 * })
 * ```
 */
export function configureBrowser(configuration: BrowserConfiguration): void {
  // __DEV__ is a React Native global; absent under plain node (vitest).
  if (typeof __DEV__ !== 'undefined' && __DEV__) {
    validateBrowserConfiguration(configuration)
  }
  currentConfiguration = configuration
  nativeBrowser.configuration = toNativeConfig(configuration)
}

/**
 * Returns the configuration last passed to {@link configureBrowser}, in its
 * original public shape (the native getter exposes only the lowered internal
 * form). `undefined` until the browser is configured.
 *
 * Treat the returned object as read-only: it is the live reference, and
 * mutating it does NOT reconfigure the browser — call configureBrowser again.
 */
export function getBrowserConfiguration(): BrowserConfiguration | undefined {
  return currentConfiguration
}

export function navigate(pathOrTrack: string | Track) {
  if (typeof pathOrTrack === 'string') {
    return nativeBrowser.navigatePath(pathOrTrack)
  } else {
    return nativeBrowser.navigateTrack(pathOrTrack)
  }
}

export function getPath() {
  return nativeBrowser.path
}

export const onPathChanged = NativeUpdatedValue.emitterize<string | undefined>(
  (cb) => (nativeBrowser.onPathChanged = cb)
)

export function usePath(): string | undefined {
  return useNativeUpdatedValue(getPath, onPathChanged)
}

export function getContent(): ResolvedTrack | undefined {
  return nativeBrowser.getContent()
}

export const onContentChanged = NativeUpdatedValue.emitterize<
  ResolvedTrack | undefined
>((cb) => (nativeBrowser.onContentChanged = cb))

export function useContent(): ResolvedTrack | undefined {
  return useNativeUpdatedValue(getContent, onContentChanged)
}
export function getTabs(): Track[] | undefined {
  return nativeBrowser.tabs
}

export const onTabsChanged = NativeUpdatedValue.emitterize<Track[] | undefined>(
  (cb) => (nativeBrowser.onTabsChanged = cb)
)

export function useTabs(): Track[] | undefined {
  return useNativeUpdatedValue(getTabs, onTabsChanged)
}

/**
 * Notifies external media controllers (Android Auto, CarPlay) that content
 * at the specified path has changed and should be refreshed.
 *
 * @param path - The path where content has changed (e.g., '/favorites')
 *
 * @example
 * ```ts
 * // After adding a track to favorites
 * notifyContentChanged('/favorites')
 * ```
 */
export const notifyContentChanged = (path: string): void => {
  nativeBrowser.notifyContentChanged(path)
}

/**
 * Invalidates all cached browse content and refreshes every currently-displayed
 * browse surface. Use when something app-wide changed (locale switch, sign-out,
 * etc.) and every browse path should re-fetch from its route handler — unlike
 * {@link notifyContentChanged}, which targets a single path.
 *
 * @example
 * ```ts
 * // After the user changes the app language
 * invalidateAllContent()
 * ```
 */
export const invalidateAllContent = (): void => {
  nativeBrowser.invalidateAllContent()
}

/**
 * Returns whether search functionality is configured via `configureBrowser({ search: ... })`.
 */
export function hasSearch(): boolean {
  const config = nativeBrowser.configuration
  const searchRoute = config.routes?.find((r) => r.path === SEARCH_ROUTE_PATH)
  return !!(searchRoute?.searchCallback || searchRoute?.searchConfig)
}

/**
 * Searches for tracks using the configured search source.
 *
 * @param query - The search query string
 * @returns Promise resolving to an array of matching tracks
 *
 * @see {@link configureBrowser}
 *
 * @example
 * ```ts
 * const results = await search('jazz')
 * console.log(`Found ${results.length} tracks`)
 * ```
 */
export async function search(query: string): Promise<Track[]> {
  return nativeBrowser.search(query)
}
