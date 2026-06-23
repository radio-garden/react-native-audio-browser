import type {
  BrowserConfiguration,
  BrowserSource,
  BrowserSourceCallback,
  RequestConfigResolver,
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

function isObjectValue(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isTransformableRequestConfig(
  source: unknown
): source is TransformableRequestConfig {
  if (!isObjectValue(source)) return false
  // Any RequestConfig key marks a request config. A static ResolvedTrack
  // shares none of these at the top level (its per-track request override is
  // nested under `request`).
  return (
    'baseUrl' in source ||
    'path' in source ||
    'headers' in source ||
    'query' in source ||
    'method' in source ||
    'body' in source ||
    'contentType' in source ||
    'userAgent' in source ||
    'transform' in source ||
    'transformSync' in source
  )
}

function isRouteConfig(source: unknown): source is RouteConfig {
  if (!isObjectValue(source)) return false
  // `browse`/`media` keys only exist on RouteConfig. `artwork` also exists on
  // a static ResolvedTrack — but there it is a string URL, while RouteConfig's
  // is a config object, so only an object-valued `artwork` marks a RouteConfig.
  return (
    'browse' in source || 'media' in source || isObjectValue(source.artwork)
  )
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

  // TransformableRequestConfig — the endpoint returns a page object whose
  // children are the tabs (same shape as a browse endpoint).
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

// Exported for hasSearch() in browser.ts, which inspects the lowered config.
// DEFAULT_ROUTE_PATH and TABS_ROUTE_PATH are not needed outside this module.
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

/** Android Auto / CarPlay display at most this many tabs. */
const MAX_TABS = 4

/**
 * Dev-only sanity checks for a BrowserConfiguration. Warns (never throws) on
 * mistakes the type system cannot catch: reserved route keys, route values
 * that match no source shape (and would be served as garbage static content),
 * and platform display limits. Called by configureBrowser under __DEV__.
 */
export function validateBrowserConfiguration(
  config: BrowserConfiguration
): void {
  const warn = (message: string) =>
    console.warn(`[react-native-audio-browser] configureBrowser: ${message}`)

  if (config.routes) {
    for (const [path, source] of Object.entries(config.routes)) {
      if (path !== '*' && path.startsWith('__')) {
        warn(
          `route "${path}" uses the reserved "__" prefix and will conflict ` +
            `with internal routing entries. Rename the route.`
        )
        continue
      }
      if (
        !isCallback(source) &&
        !isRouteConfig(source) &&
        !isTransformableRequestConfig(source)
      ) {
        // Falls through to the static-page branch; a page needs url + title.
        const page = source as Partial<ResolvedTrack>
        if (typeof page.url !== 'string' || typeof page.title !== 'string') {
          warn(
            `route "${path}" matched no source shape — expected a callback, ` +
              `a request config ({ baseUrl, path, headers, ... }), a ` +
              `RouteConfig ({ browse, media, artwork }), or a static page ` +
              `({ url, title, children? }). It will be served as static ` +
              `content and likely render a blank screen.`
          )
        }
      }
    }
  }

  if (Array.isArray(config.tabs) && config.tabs.length > MAX_TABS) {
    warn(
      `${config.tabs.length} tabs configured; Android Auto and CarPlay ` +
        `display at most ${MAX_TABS} — extra tabs will not be shown.`
    )
  }
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
    carPlayLoadingTitle: config.carPlayLoadingTitle,
    resolveAlbumUrl: config.resolveAlbumUrl,
    formatNavigationError: config.formatNavigationError
  }
}
