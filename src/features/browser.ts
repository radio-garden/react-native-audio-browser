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
 * - Playing a Playable track selected with navigate()
 *
 * Calling this again REPLACES the entire configuration (no merging) and
 * re-runs initial navigation: the in-app browser returns to `path` or the
 * first tab (on web, the first tab only when `tabs` is an array; otherwise
 * '/'). CarPlay and Android Auto keep their place. For values that change at
 * runtime (base URL, locale, auth token), don't reconfigure: read them
 * inside a `request`/`browse` resolver or a `transform`, and call
 * {@link invalidateAllContent} so every surface re-fetches in place.
 *
 * @example
 * ```ts
 * configureBrowser({
 *   request: { baseUrl: 'https://api.example.com' },
 *   routes: {
 *     // `{id}` is a route parameter — see the `routes` docs for matching
 *     // rules.
 *     '/albums/{id}': async ({ routeParams }) =>
 *       fetchAlbumPage(routeParams?.id),
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

/**
 * Opens a Path in the in-app browser, or selects a Track.
 *
 * A string opens that Path; {@link getPath} and {@link getContent} and their
 * events update. A Track is a selection: a Playable track plays, by default
 * with its page as the queue (see `singleTrack` and `handleTrackLoad` on
 * {@link BrowserConfiguration}); a Browsable track opens its path; a disabled
 * track is ignored.
 */
export function navigate(pathOrTrack: string | Track) {
  if (typeof pathOrTrack === 'string') {
    return nativeBrowser.navigatePath(pathOrTrack)
  } else {
    return nativeBrowser.navigateTrack(pathOrTrack)
  }
}

/**
 * The Path the in-app browser shows. Before the first navigation it is '/' on
 * iOS and Android and `undefined` on web.
 */
export function getPath() {
  return nativeBrowser.path
}

/** Emits the new Path when the in-app browser navigates. */
export const onPathChanged = NativeUpdatedValue.emitterize<string | undefined>(
  (cb) => (nativeBrowser.onPathChanged = cb)
)

/** Hook that returns {@link getPath} and updates on {@link onPathChanged}. */
export function usePath(): string | undefined {
  return useNativeUpdatedValue(getPath, onPathChanged)
}

/**
 * The resolved page the in-app browser shows; `undefined` until the first
 * navigation resolves.
 */
export function getContent(): ResolvedTrack | undefined {
  return nativeBrowser.getContent()
}

/** Emits the new page when the in-app browser's content changes. */
export const onContentChanged = NativeUpdatedValue.emitterize<
  ResolvedTrack | undefined
>((cb) => (nativeBrowser.onContentChanged = cb))

/**
 * Hook that returns {@link getContent} and updates on {@link onContentChanged}.
 */
export function useContent(): ResolvedTrack | undefined {
  return useNativeUpdatedValue(getContent, onContentChanged)
}

/**
 * The configured tabs, resolved; `undefined` until {@link configureBrowser}
 * has run and the tabs have resolved.
 */
export function getTabs(): Track[] | undefined {
  return nativeBrowser.tabs
}

/** Emits the new tabs when they resolve or change. */
export const onTabsChanged = NativeUpdatedValue.emitterize<Track[] | undefined>(
  (cb) => (nativeBrowser.onTabsChanged = cb)
)

/** Hook that returns {@link getTabs} and updates on {@link onTabsChanged}. */
export function useTabs(): Track[] | undefined {
  return useNativeUpdatedValue(getTabs, onTabsChanged)
}

/**
 * Re-fetches the content at `path` on every surface showing it (the in-app
 * browser, Android Auto, CarPlay); other paths stay as they are. On iOS and
 * Android it also drops the path's cached page. Pass a container path such as
 * '/favorites', not a track's Contextual Path, which iOS and Android reject.
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
 * Returns whether search functionality is configured via
 * `configureBrowser({ search: ... })`.
 */
export function hasSearch(): boolean {
  const config = nativeBrowser.configuration
  const searchRoute = config.routes?.find((r) => r.path === SEARCH_ROUTE_PATH)
  return !!(searchRoute?.searchCallback || searchRoute?.searchConfig)
}

/**
 * Searches for tracks using the configured search source.
 *
 * When a search source is configured, resolves `[]` only when it found
 * nothing; a failed search rejects, so a caller can tell "no results" from
 * "the request failed". Exception: on Android an HTTP search source
 * (`TransformableRequestConfig`) resolves `[]` on any failure (non-2xx,
 * network or decode error); a callback source still rejects there. With no
 * search source configured, Android and web resolve `[]` and iOS rejects;
 * call {@link hasSearch} first.
 *
 * The rejection's message describes the failure; on native platforms JS sees
 * only that message. On web, a failed search request's `Error` also carries a
 * `code`, the
 * {@link features/errors!NavigationErrorType | NavigationErrorType} the
 * browse path reports as a
 * {@link features/errors!NavigationError | NavigationError}
 * (`'http-error'`, `'network-error'`); a search callback's rejection passes
 * through unchanged.
 *
 * @throws When the search source or its request fails, or on iOS when no
 *   search source is configured
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
