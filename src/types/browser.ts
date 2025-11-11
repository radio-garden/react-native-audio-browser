import type { ResolvedTrack, Track } from './browser-nodes'

export type BrowserSourceCallbackParam = {
  path: string
  routeParams?: Record<string, string>
}

export type BrowserSourceCallback = (
  param: BrowserSourceCallbackParam
) => Promise<ResolvedTrack>
export type SearchSourceCallback = (query: string) => Promise<Track[]>
export type RequestConfigTransformer = (
  request: RequestConfig,
  routeParams?: Record<string, string>
) => Promise<RequestConfig>

export type HttpMethod =
  | 'GET'
  | 'POST'
  | 'PUT'
  | 'DELETE'
  | 'PATCH'
  | 'HEAD'
  | 'OPTIONS'

export interface RequestConfig {
  method?: HttpMethod
  path?: string
  baseUrl?: string
  headers?: Record<string, string>
  query?: Record<string, string>
  body?: string
  contentType?: string
  userAgent?: string
}

export interface TransformableRequestConfig extends RequestConfig {
  transform?: RequestConfigTransformer
}

export interface MediaRequestConfig extends TransformableRequestConfig {
  resolve?: (track: Track) => Promise<RequestConfig>
}

export type BrowserSource =
  | ResolvedTrack
  | BrowserSourceCallback
  | TransformableRequestConfig

export type RouteSource = BrowserSourceCallback | TransformableRequestConfig

export type TabsSourceCallback = () => Promise<Track[]>
/**
 * Tab source configuration for navigation tabs.
 *
 * When using API configuration (TransformableRequestConfig), the request path defaults to '/'
 * and should return an array of Track objects with urls representing the tabs.
 */
export type TabsSource =
  | Track[]
  | TabsSourceCallback
  | TransformableRequestConfig

/**
 * Search source configuration for handling search requests.
 *
 * Can be either:
 * - SearchSourceCallback: Custom function that receives query string and returns Track[]
 * - TransformableRequestConfig: API configuration where query will be automatically added as { q: query } to request.query
 */
export type SearchSource = SearchSourceCallback | TransformableRequestConfig

export type PlayConfigurationBehavior = 'single' | 'queue'

export type PlayConfigurationHandler = (
  track: Track,
  parent?: ResolvedTrack
) => void

export type BrowserConfiguration = {
  /**
   * Initial navigation path. Setting this triggers initial navigation to the specified path.
   */
  path?: string | undefined

  /**
   * Base request configuration applied to all HTTP requests.
   * Merged with specific configurations (browse, search, media) where specific settings override base settings.
   * Useful for shared settings like user agent, common headers / request parameters (e.g., API keys, locale), or base URL.
   */
  request?: RequestConfig

  media?: MediaRequestConfig

  /**
   * Configuration for search functionality.
   * Enables search capabilities in the media browser, typically accessed through voice commands or search UI.
   *
   * Optional - if not provided, search functionality will be disabled.
   * Required for Android Auto/CarPlay voice search integration.
   *
   * Can be either:
   * - SearchSourceCallback: Custom function for complex search logic
   * - TransformableRequestConfig: API endpoint where query is automatically added as { q: query } to request.query
   *   (use transform callback to place query in headers, body, or custom parameter names)
   *
   * @example
   * ```typescript
   * search: {
   *   baseUrl: 'https://api.example.com/search',
   *   transform(request) {
   *     return {
   *       ...request,
   *       query: { ...request.query, limit: 20 }
   *     };
   *   }
   * }
   * ```
   */
  search?: SearchSource

  /**
   * Configuration for navigation tabs in the media browser.
   * The first tab's URL is automatically loaded when the browser starts.
   *
   * Optional - if not provided, no tab navigation will be available.
   * Limited to maximum 4 tabs for automotive platform compatibility (Android Auto/CarPlay).
   *
   * Can provide static array of Track objects with urls as tabs, API configuration, or custom callback.
   */
  tabs?: TabsSource

  /**
   * Route-specific configurations for handling navigation paths.
   * Maps URL paths to their corresponding sources (static content, API configs, or callbacks).
   *
   * Optional - if not provided, all navigation uses the browse fallback.
   * Routes match paths that start with the route key, with specificity (most slashes wins).
   *
   * @example
   * ```typescript
   * routes: {
   *   '/favorites': {
   *     transform: async (request) => ({
   *       ...request,
   *       body: JSON.stringify({ ids: await getFavoriteIds() })
   *     })
   *   },
   *   '/artists': { baseUrl: 'https://music-api.com' },
   *   '/artists/premium': { baseUrl: 'https://premium-api.com' } // More specific, wins for /artists/premium/*
   * }
   * ```
   */
  routes?: Record<string, BrowserSource>

  browse?: BrowserSource

  /**
   * Configuration for track playback behavior when user selects a track.
   * Controls how the audio queue is set up when playback starts.
   *
   * Can be either a simple behavior string or a custom handler function:
   * - 'queue': Replace queue with all tracks from parent context, start at selected track (default)
   * - 'single': Replace queue with just the selected track
   *
   * @default 'queue'
   */
  play?: PlayConfigurationBehavior
}
