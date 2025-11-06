import type {
  BrowserItem,
  BrowserLink,
  BrowserList,
  Track,
} from './browser-nodes'

export type BrowserSourceCallbackParam = {
  path: string
}

export type BrowserSourceCallback = (
  param: BrowserSourceCallbackParam
) => Promise<BrowserList>
export type SearchSourceCallback = (query: string) => Promise<Track[]>
export type RequestConfigTransformer = (
  request: RequestConfig
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

export type BrowserSource =
  | BrowserList
  | BrowserSourceCallback
  | TransformableRequestConfig

export type RouteSource = BrowserSourceCallback | TransformableRequestConfig

/**
 * Tab source configuration for navigation tabs.
 *
 * When using API configuration (TransformableRequestConfig), the request path defaults to '/'
 * and should return a MediaList with MediaLink children representing the tabs.
 */
export type TabsSource =
  | BrowserLink[]
  | BrowserSourceCallback
  | TransformableRequestConfig

/**
 * Search source configuration for handling search requests.
 *
 * Can be either:
 * - SearchSourceCallback: Custom function that receives query string and returns MediaItem[]
 * - TransformableRequestConfig: API configuration where query will be automatically added as { q: query } to request.query
 */
export type SearchSource = SearchSourceCallback | TransformableRequestConfig

export type MediaSource = TransformableRequestConfig

export type BrowserConfig = {
  /**
   * Base request configuration applied to all HTTP requests.
   * Merged with specific configurations (browse, search, media) where specific settings override base settings.
   * Useful for shared settings like user agent, common headers / request parameters (e.g., API keys, locale), or base URL.
   */
  request?: RequestConfig

  /**
   * Configuration for media/stream requests.
   * Used for fetching actual audio/video files referenced in MediaItem.src URLs.
   * Handles the final step of media playback - transforming media URLs into playable streams.
   *
   * Optional - if not provided, MediaItem.src URLs are used directly without transformation.
   * Useful when media files are already accessible at their src URLs without additional processing.
   *
   * Common use cases:
   * - Adding authentication headers for protected content
   * - CDN routing and URL rewriting
   * - Format negotiation (HLS vs MP4)
   *
   * @example
   * ```typescript
   * media: {
   *   baseUrl: 'https://cdn.example.com',
   *   transform(request) {
   *     return {
   *       ...request,
   *       headers: { 'Authorization': `Bearer ${getAuthToken()}` },
   *       path: request.path?.replace('/stream/', '/hls/')
   *     };
   *   }
   * }
   * ```
   */
  media?: MediaSource

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

  /**
   * Configuration for navigation tabs in the media browser.
   * The first tab's URL is automatically loaded when the browser starts.
   *
   * Optional - if not provided, no tab navigation will be available.
   * Limited to maximum 4 tabs for automotive platform compatibility (Android Auto/CarPlay).
   *
   * Can provide static array of MediaLink objects as tabs, API configuration, or custom callback.
   */
  tabs?: TabsSource

  /**
   * Default handler for browse/navigation requests.
   * Used as fallback when a path doesn't have a specific source defined in routes.
   * Can be API configuration or custom callback function.
   *
   * Optional - if not provided, navigation to undefined paths will fail.
   * Typically used when most navigation can be handled by a single API endpoint.
   */
  browse?: BrowserSource

  // /**
  //  * Configuration for media/stream requests.
  //  * Used for fetching actual audio/video files referenced in MediaItem.src URLs.
  //  * Handles the final step of media playback - transforming media URLs into playable streams.
  //  *
  //  * Optional - if not provided, MediaItem.src URLs are used directly without transformation.
  //  * Useful when media files are already accessible at their src URLs without additional processing.
  //  *
  //  * Common use cases:
  //  * - Adding authentication headers for protected content
  //  * - CDN routing and URL rewriting
  //  * - Format negotiation (HLS vs MP4)
  //  *
  //  * @example
  //  * ```typescript
  //  * media: {
  //  *   baseUrl: 'https://cdn.example.com',
  //  *   transform(request) {
  //  *     return {
  //  *       ...request,
  //  *       headers: { 'Authorization': `Bearer ${getAuthToken()}` },
  //  *       path: request.path?.replace('/stream/', '/hls/')
  //  *     };
  //  *   }
  //  * }
  //  * ```
  //  */
  // media?: MediaSource
}

export type Browser = {
  navigate(path: string): void
  getCurrentItem(): BrowserItem
  search(query: string): Promise<Track[]>
}
