import type { ResolvedTrack, Track } from './browser-nodes'

export type BrowserSourceCallbackParam = {
  path: string
  routeParams?: Record<string, string>
}

export type BrowserSourceCallback = (
  param: BrowserSourceCallbackParam
) => Promise<ResolvedTrack>

/**
 * Search mode types for structured voice search.
 *
 * - `any`: Play any content - smart shuffle or last playlist (query will be empty string)
 * - `genre`: Search by genre
 * - `artist`: Search by artist
 * - `album`: Search by album
 * - `song`: Search by song/track title
 * - `playlist`: Search by playlist name
 *
 * @see BrowserConfiguration.search
 * @see SearchParams
 */
export type SearchMode =
  | 'any'
  | 'genre'
  | 'artist'
  | 'album'
  | 'song'
  | 'playlist'

/**
 * Structured search parameters from voice commands.
 *
 * Voice commands are parsed by Android into structured parameters:
 * - "play something" → mode=null, query="something"
 * - "play music" → mode='any', query=""
 * - "play jazz" → mode='genre', genre="jazz", query="jazz"
 * - "play michael jackson" → mode='artist', artist="michael jackson", query="michael jackson"
 * - "play thriller by michael jackson" → mode='album', album="thriller", artist="michael jackson"
 * - "play billie jean" → mode='song', title="billie jean", query="billie jean"
 */
export interface SearchParams {
  /** The search mode indicating what type of search is being performed, or null for unstructured search */
  mode?: SearchMode
  /**
   * The original search query string (always present, but may be empty string "").
   * When mode='any' with empty string query, return any content you think the user would like
   * (e.g., recently played, favorites, or smart shuffle).
   */
  query: string
  /** Genre name for genre-specific search */
  genre?: string
  /** Artist name for artist/album/song search */
  artist?: string
  /** Album name for album-specific search */
  album?: string
  /** Song title for song-specific search */
  title?: string
  /** Playlist name for playlist-specific search */
  playlist?: string
}

export type SearchSourceCallback = (params: SearchParams) => Promise<Track[]>
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
 * @see BrowserConfiguration.search
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
   * Required for Android Auto/CarPlay voice search integration with support for structured voice commands.
   *
   * Search receives structured parameters from voice commands like:
   * - "play music" → mode='any', query=""
   * - "play jazz" → mode='genre', genre="jazz", query="jazz"
   * - "play michael jackson" → mode='artist', artist="michael jackson", query="michael jackson"
   * - "play thriller by michael jackson" → mode='album', album="thriller", artist="michael jackson"
   * - "play billie jean" → mode='song', title="billie jean", query="billie jean"
   *
   * Can be either:
   * - SearchSourceCallback: Receives SearchParams with query, and optional mode/artist/album/genre/title/playlist fields
   * - TransformableRequestConfig: API endpoint where all search parameters are automatically added to request.query:
   *   - q: search query string (always present)
   *   - mode: search mode (any/genre/artist/album/song/playlist) - omitted for unstructured search
   *   - artist, album, genre, title, playlist: included when present
   *
   * @example
   * ```typescript
   * // Callback approach - direct access to structured parameters
   * search: async (params) => {
   *   // Use structured fields for precise searches
   *   if (params.mode === 'artist' && params.artist) {
   *     return await db.query('SELECT * FROM tracks WHERE artist = ?', [params.artist]);
   *   }
   *   if (params.mode === 'album' && params.album && params.artist) {
   *     return await db.query('SELECT * FROM tracks WHERE album = ? AND artist = ?',
   *       [params.album, params.artist]);
   *   }
   *   // Fall back to full-text search
   *   return await searchByQuery(params.query);
   * }
   *
   * // API configuration - parameters automatically added to query string
   * search: {
   *   baseUrl: 'https://api.example.com/search',
   *   // GET /search?q=thriller&mode=album&album=thriller&artist=michael+jackson&limit=20
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
