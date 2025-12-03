import type { ResolvedTrack, Track } from './browser-nodes.ts'

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

/**
 * Request configuration that supports async transformation.
 * Extends RequestConfig with a transform callback for dynamic request modification.
 *
 * The transform callback receives the merged request config and can modify it
 * before the request is made. This is useful for adding dynamic headers,
 * signing URLs, or other request-time modifications.
 *
 * @example
 * ```typescript
 * const config: TransformableRequestConfig = {
 *   baseUrl: 'https://api.example.com',
 *   transform: async (request) => ({
 *     ...request,
 *     headers: {
 *       ...request.headers,
 *       'Authorization': `Bearer ${await getAccessToken()}`
 *     }
 *   })
 * }
 * ```
 */
export interface TransformableRequestConfig extends RequestConfig {
  transform?: RequestConfigTransformer
}

/**
 * Configuration for media resource requests (audio streams, artwork images).
 * Extends TransformableRequestConfig with per-track resolution capabilities.
 *
 * Used for both `media` (audio streaming) and `artwork` (image loading) configuration
 * in BrowserConfiguration.
 *
 * ## Configuration Hierarchy
 *
 * When a request is made, configs are merged in this order (later overrides earlier):
 * 1. `request` (base config) - shared settings like user agent, common headers
 * 2. `media`/`artwork` config - resource-specific settings
 * 3. `resolve(track)` result - per-track overrides (if provided)
 * 4. `transform(request)` result - final modifications (if provided)
 *
 * ## Usage Patterns
 *
 * **Simple CDN configuration:**
 * ```typescript
 * media: {
 *   baseUrl: 'https://audio.cdn.example.com',
 *   headers: { 'X-API-Key': 'your-api-key' }
 * }
 * ```
 *
 * **Per-track URL resolution:**
 * ```typescript
 * media: {
 *   resolve: async (track) => ({
 *     baseUrl: 'https://audio.cdn.example.com',
 *     path: `/streams/${track.src}`,
 *     query: { token: await getSignedToken(track.src) }
 *   })
 * }
 * ```
 *
 * **Dynamic request signing:**
 * ```typescript
 * artwork: {
 *   baseUrl: 'https://images.cdn.example.com',
 *   transform: async (request) => ({
 *     ...request,
 *     query: { ...request.query, sig: await signUrl(request.path) }
 *   })
 * }
 * ```
 *
 * @see BrowserConfiguration.media - Audio stream configuration
 * @see BrowserConfiguration.artwork - Image/artwork configuration
 */
export interface MediaRequestConfig extends TransformableRequestConfig {
  /**
   * Per-track request resolution callback.
   *
   * Called for each track to generate the final request configuration.
   * Receives the full Track object, allowing URL generation based on
   * track metadata (artist, album, src, etc.).
   *
   * The returned config is merged with base configs, then passed to
   * `transform` if provided.
   *
   * @param track - The track being requested
   * @returns Request configuration for this specific track
   *
   * @example
   * ```typescript
   * resolve: async (track) => ({
   *   path: `/audio/${track.artist}/${track.album}/${track.src}`,
   *   query: { quality: 'high' }
   * })
   * ```
   */
  resolve?: (track: Track) => Promise<RequestConfig>
}

export type BrowserSource =
  | ResolvedTrack
  | BrowserSourceCallback
  | TransformableRequestConfig

export type RouteSource = BrowserSourceCallback | TransformableRequestConfig

/**
 * Route configuration with per-route media and artwork overrides.
 *
 * @example
 * ```typescript
 * routes: {
 *   '/premium': {
 *     browse: async () => fetchPremiumContent(),
 *     media: { baseUrl: 'https://premium-audio.cdn.com' },
 *     artwork: { baseUrl: 'https://premium-images.cdn.com' }
 *   }
 * }
 * ```
 */
export type RouteConfig = {
  /** Override browse config for this route. */
  browse?: BrowserSource
  /** Override media config for this route. */
  media?: MediaRequestConfig
  /** Override artwork config for this route. */
  artwork?: MediaRequestConfig
}

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

export type BrowserConfiguration = {
  /**
   * Initial navigation path. Setting this triggers initial navigation to the specified path.
   */
  path?: string | undefined

  // ─── Request Defaults (applied to all requests) ────────────────────────────

  /**
   * Shared request settings applied to all HTTP requests (browse, search, media, artwork).
   * Specific configs override these defaults.
   */
  request?: TransformableRequestConfig

  // ─── Content Configuration ─────────────────────────────────────────────────

  /** Default browse source when no matching route is found. */
  browse?: BrowserSource

  /** Media/audio stream request configuration. */
  media?: MediaRequestConfig

  /**
   * Configuration for artwork/image requests.
   * Used to transform artwork URLs for CDNs that require different authentication tokens,
   * base URLs, or query parameters than audio requests.
   *
   * Artwork URLs are transformed when tracks are processed (before being passed to media controllers
   * like Android Auto). This is different from media requests which are transformed at playback time.
   *
   * Note: Since media controllers load images directly from URLs, HTTP headers cannot be applied
   * to artwork requests. Use query parameters for authentication tokens if your CDN supports it.
   *
   * @example
   * ```typescript
   * // Different CDN for images with signed URL parameters
   * artwork: {
   *   baseUrl: 'https://images.cdn.example.com',
   *   query: { token: 'image-auth-token' }
   * }
   *
   * // Per-track artwork URL resolution using track metadata
   * artwork: {
   *   resolve: async (track) => ({
   *     baseUrl: 'https://images.cdn.example.com',
   *     path: `/covers/${track.artist}/${track.album}.jpg`,
   *     query: { token: await getSignedToken(track) }
   *   })
   * }
   * ```
   */
  artwork?: MediaRequestConfig

  // ─── Navigation ────────────────────────────────────────────────────────────

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
   * Route-specific configurations. Maps URL paths to browse sources.
   * Routes match by prefix, most specific (most slashes) wins.
   *
   * Can be a simple `BrowserSource` or extended `RouteConfig` with media/artwork overrides.
   *
   * @example
   * ```typescript
   * routes: {
   *   '/favorites': async () => getFavorites(),
   *   '/artists': { baseUrl: 'https://music-api.com' },
   *   '/premium': {
   *     browse: { baseUrl: 'https://premium-api.com' },
   *     artwork: { baseUrl: 'https://premium-images.cdn.com' }
   *   }
   * }
   * ```
   */
  routes?: Record<string, BrowserSource | RouteConfig>

  // ─── Behavior ──────────────────────────────────────────────────────────────

  /**
   * When true, only play the selected track without queuing siblings.
   * When false (default), replace queue with all tracks from parent context and start at selected track.
   *
   * @default false
   */
  singleTrack?: boolean

  /**
   * Show an offline error message in external controllers (Android Auto, Wear OS, Automotive)
   * when network connectivity is lost.
   *
   * When enabled, displays a standard offline error item in the media browser
   * instead of the normal content when the network is offline.
   *
   * Only applies to external Media3 controllers, not in-app browsing.
   *
   * @default true
   * @platform android
   */
  androidControllerOfflineError?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// Native Configuration Types
// ─────────────────────────────────────────────────────────────────────────────
// These types are used internally by the native bridge. They flatten union types
// into separate optional properties to reduce generated code complexity.
// Users should use BrowserConfiguration, not these types directly.

/**
 * Flattened route entry for native bridge.
 * @internal
 */
export interface NativeRouteEntry {
  path: string
  // Browse source (flattened)
  browseCallback?: BrowserSourceCallback
  browseConfig?: TransformableRequestConfig
  browseStatic?: ResolvedTrack
  // Search source (only used for __search__ route)
  searchCallback?: SearchSourceCallback
  searchConfig?: TransformableRequestConfig
  // Per-route media/artwork config
  media?: MediaRequestConfig
  artwork?: MediaRequestConfig
}

/**
 * Flattened browser configuration for native bridge.
 * Converts union types to separate optional properties for simpler native code generation.
 * @internal
 */
export interface NativeBrowserConfiguration {
  path?: string

  // Request defaults
  request?: TransformableRequestConfig

  // Global media/artwork config (applied when route doesn't override)
  media?: MediaRequestConfig
  artwork?: MediaRequestConfig

  // Routes as array - includes:
  // - Explicit routes from config.routes
  // - Root browse as __default__ entry
  // - Tabs as __tabs__ entry (returns ResolvedTrack with children for navigation tabs)
  // - Search as __search__ entry (has searchCallback or searchConfig)
  routes?: NativeRouteEntry[]

  // Behavior
  singleTrack?: boolean
  androidControllerOfflineError?: boolean
}
