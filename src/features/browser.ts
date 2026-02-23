import type {
  BrowserConfiguration,
  BrowserSource,
  BrowserSourceCallback,
  ResolvedTrack,
  RouteConfig,
  SearchSource,
  SearchSourceCallback,
  TabsSource,
  Track,
  TransformableRequestConfig
} from '../types'
import type {
  NativeBrowserConfiguration,
  NativeRouteEntry
} from '../types/browser-native'
import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

// ─────────────────────────────────────────────────────────────────────────────
// Configuration Transformation
// ─────────────────────────────────────────────────────────────────────────────

function isCallback(
  source: unknown
): source is (...args: unknown[]) => unknown {
  return typeof source === 'function'
}

function isTransformableRequestConfig(
  source: unknown
): source is TransformableRequestConfig {
  if (typeof source !== 'object' || source === null) return false
  const obj = source as Record<string, unknown>
  // Has request config properties (not a ResolvedTrack which has 'title')
  return (
    'baseUrl' in obj ||
    'path' in obj ||
    'headers' in obj ||
    'query' in obj ||
    'transform' in obj
  )
}

function isRouteConfig(source: unknown): source is RouteConfig {
  if (typeof source !== 'object' || source === null) return false
  const obj = source as Record<string, unknown>
  // RouteConfig has browse/media/artwork properties at the top level
  return 'browse' in obj || ('media' in obj && !('baseUrl' in obj))
}

function flattenBrowseSource(source: BrowserSource | undefined): {
  browseCallback?: NativeRouteEntry['browseCallback']
  browseConfig?: NativeRouteEntry['browseConfig']
  browseStatic?: NativeRouteEntry['browseStatic']
} {
  if (!source) return {}
  if (isCallback(source))
    return { browseCallback: source as NativeRouteEntry['browseCallback'] }
  if (isTransformableRequestConfig(source))
    return { browseConfig: source as NativeRouteEntry['browseConfig'] }
  return { browseStatic: source as NativeRouteEntry['browseStatic'] }
}

/**
 * Converts a SearchSource to a NativeRouteEntry for the __search__ path.
 */
function searchSourceToRouteEntry(source: SearchSource): NativeRouteEntry {
  if (isCallback(source)) {
    return {
      path: SEARCH_ROUTE_PATH,
      searchCallback: source as SearchSourceCallback
    }
  }
  return {
    path: SEARCH_ROUTE_PATH,
    searchConfig: source as TransformableRequestConfig
  }
}

/**
 * Wraps a Track[] into a ResolvedTrack for tabs.
 * Tabs are represented as a special route that returns children.
 */
function wrapTracksAsResolvedTrack(tracks: Track[]): ResolvedTrack {
  return {
    url: TABS_ROUTE_PATH,
    title: 'Tabs',
    children: tracks
  }
}

/**
 * Converts a TabsSource to a NativeRouteEntry for the __tabs__ path.
 * Track[] is wrapped in ResolvedTrack, callbacks are wrapped to return ResolvedTrack.
 */
function tabsSourceToRouteEntry(source: TabsSource): NativeRouteEntry {
  if (Array.isArray(source)) {
    // Static Track[] - wrap as ResolvedTrack
    return {
      path: TABS_ROUTE_PATH,
      browseStatic: wrapTracksAsResolvedTrack(source)
    }
  }

  if (isCallback(source)) {
    // Callback returning Track[] - wrap to return ResolvedTrack
    const wrappedCallback: BrowserSourceCallback = async () => {
      const tracks = await source()
      return wrapTracksAsResolvedTrack(tracks)
    }
    return {
      path: TABS_ROUTE_PATH,
      browseCallback: wrappedCallback
    }
  }

  // TransformableRequestConfig - native will handle wrapping the response
  return {
    path: TABS_ROUTE_PATH,
    browseConfig: source
  }
}

function flattenRouteEntry(
  path: string,
  source: BrowserSource | RouteConfig
): NativeRouteEntry {
  if (isRouteConfig(source)) {
    return {
      path,
      ...flattenBrowseSource(source.browse),
      media: source.media,
      artwork: source.artwork
    }
  }
  return { path, ...flattenBrowseSource(source) }
}

/** Internal path used for the default/root browse source */
const DEFAULT_ROUTE_PATH = '__default__'

/** Internal path used for navigation tabs */
const TABS_ROUTE_PATH = '__tabs__'

/** Internal path used for search */
const SEARCH_ROUTE_PATH = '__search__'

function flattenRoutes(
  routes: Record<string, BrowserSource | RouteConfig> | undefined,
  rootBrowse: BrowserSource | undefined,
  tabs: TabsSource | undefined,
  search: SearchSource | undefined
): NativeRouteEntry[] | undefined {
  const entries: NativeRouteEntry[] = []

  // Add explicit routes
  if (routes) {
    for (const [path, source] of Object.entries(routes)) {
      entries.push(flattenRouteEntry(path, source))
    }
  }

  // Add root browse as default fallback
  if (rootBrowse) {
    entries.push(flattenRouteEntry(DEFAULT_ROUTE_PATH, rootBrowse))
  }

  // Add tabs as special route
  if (tabs) {
    entries.push(tabsSourceToRouteEntry(tabs))
  }

  // Add search as special route
  if (search) {
    entries.push(searchSourceToRouteEntry(search))
  }

  return entries.length > 0 ? entries : undefined
}

function toNativeConfig(
  config: BrowserConfiguration
): NativeBrowserConfiguration {
  return {
    path: config.path,
    request: config.request,
    media: config.media,
    artwork: config.artwork,
    routes: flattenRoutes(
      config.routes,
      config.browse,
      config.tabs,
      config.search
    ),
    singleTrack: config.singleTrack,
    androidControllerOfflineError: config.androidControllerOfflineError,
    carPlayUpNextButton: config.carPlayUpNextButton,
    carPlayNowPlayingButtons: config.carPlayNowPlayingButtons,
    formatNavigationError: config.formatNavigationError
  }
}

/**
 * Configures the browser with routes, tabs, and other settings.
 * Also registers the browser with the player to enable:
 * - Media URL transformation for authenticated playback
 * - Android Auto / CarPlay browsing integration
 * - Playback of browsable tracks via navigate()
 *
 * @param configuration - Browser configuration including routes, tabs, media config, etc.
 *
 * @example
 * ```ts
 * configureBrowser({
 *   routes: {
 *     '/albums/:id': { path: '/api/albums/:id' }
 *   },
 *   tabs: [
 *     { title: 'Home', url: '/' },
 *     { title: 'Search', url: '/search' }
 *   ]
 * })
 * ```
 */
export function configureBrowser(configuration: BrowserConfiguration): void {
  nativeBrowser.configuration = toNativeConfig(configuration)
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
  return nativeBrowser.onSearch(query)
}
