import type {
  BrowserConfiguration,
  BrowserSource,
  BrowserSourceCallback,
  RequestConfigResolver,
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

// ─────────────────────────────────────────────────────────────────────────────
// Configuration Transformation
//
// Lowers the public BrowserConfiguration into the flattened native shape.
// Deliberately NOT re-exported from features/index.ts — these helpers are
// internal (and unit-tested directly), not public API.
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
    'transform' in obj ||
    'transformSync' in obj
  )
}

function isRouteConfig(source: unknown): source is RouteConfig {
  if (typeof source !== 'object' || source === null) return false
  const obj = source as Record<string, unknown>
  // RouteConfig has browse/media/artwork properties at the top level
  return 'browse' in obj || ('media' in obj && !('baseUrl' in obj))
}

function splitLayer(
  layer: TransformableRequestConfig | RequestConfigResolver | undefined
): {
  config?: TransformableRequestConfig
  resolver?: () => Promise<TransformableRequestConfig>
} {
  if (!layer) return {}
  // A function is a resolver thunk; an object is a static layer config. (The
  // `request`/`browse` layer fields are object-only today, so this is
  // unambiguous — unlike route/search sources which already accept a callback.)
  if (isCallback(layer)) {
    const resolve = layer as RequestConfigResolver
    // Normalize the sync-or-async resolver to Promise-only for the bridge so it
    // never sees a `T | Promise<T>` variant (which misreads the async case).
    // Resolvers run once per content generation, so the wrap costs nothing.
    return { resolver: () => Promise.resolve(resolve()) }
  }
  return { config: layer as TransformableRequestConfig }
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
function wrapTracksAsResolvedTrack(tracks: Track[]) {
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
export const SEARCH_ROUTE_PATH = '__search__'

function flattenRoutes(
  routes: Record<string, BrowserSource | RouteConfig> | undefined,
  tabs: TabsSource | undefined,
  search: SearchSource | undefined
): NativeRouteEntry[] | undefined {
  const entries: NativeRouteEntry[] = []

  // Add explicit routes. The '*' key is the optional custom default — it maps to
  // the __default__ entry (used when no other route matches). Without it, an
  // unmatched browse path is fetched via request + browse config applied to the
  // path (handled natively), so no __default__ entry is needed.
  if (routes) {
    for (const [path, source] of Object.entries(routes)) {
      entries.push(
        flattenRouteEntry(path === '*' ? DEFAULT_ROUTE_PATH : path, source)
      )
    }
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

export function toNativeConfig(
  config: BrowserConfiguration
): NativeBrowserConfiguration {
  const request = splitLayer(config.request)
  const browse = splitLayer(config.browse)
  return {
    path: config.path,
    request: request.config,
    requestResolver: request.resolver,
    browse: browse.config,
    browseResolver: browse.resolver,
    media: config.media,
    artwork: config.artwork,
    nowPlayingArtwork: config.nowPlayingArtwork,
    routes: flattenRoutes(config.routes, config.tabs, config.search),
    singleTrack: config.singleTrack,
    handleTrackLoad: config.handleTrackLoad,
    androidControllerOfflineError: config.androidControllerOfflineError,
    carPlayUpNextButton: config.carPlayUpNextButton,
    carPlayNowPlayingButtons: config.carPlayNowPlayingButtons,
    formatNavigationError: config.formatNavigationError
  }
}
