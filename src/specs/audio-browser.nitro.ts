import { type HybridObject } from 'react-native-nitro-modules';

import type {
  BrowserList,
  BrowserSource,
  MediaSource,
  RequestConfig,
  SearchSource,
  TabsSource,
  Track,
} from '../types';

export interface AudioBrowser
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
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

  // Browser navigation methods
  navigate(path: string): Promise<BrowserList>
  onSearch(query: string): Promise<Track[]>
  getCurrentPath(): string
}
