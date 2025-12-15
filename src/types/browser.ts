import type { NavigationError } from '../features/errors'
import type { ResolvedTrack, Track } from './browser-nodes.ts'

export type BrowserSourceCallbackParam = {
  path: string
  routeParams?: Record<string, string>
}

/**
 * Return BrowseError from a browse callback to display an error to the user.
 * The error message will be shown in an error dialog in CarPlay and Android Auto.
 *
 * On the app side, the error is surfaced as a `NavigationError` with
 * `code: 'callback-error'`. Use `useNavigationError()` to get the error details,
 * or `useFormattedNavigationError()` for a display-friendly version.
 *
 * @example
 * ```ts
 * browse: async ({ path }) => {
 *   if (!user.subscribed) {
 *     return { error: 'Please subscribe to access this content' }
 *   }
 *   return fetchContent(path)
 * }
 * ```
 */
export type BrowseError = {
  error: string
}

/**
 * Result type for browse callbacks.
 * Can be either a ResolvedTrack (success) or BrowseError (failure).
 */
export type BrowseResult = ResolvedTrack | BrowseError

export type BrowserSourceCallback = (
  param: BrowserSourceCallbackParam
) => Promise<BrowseResult>

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
  /**
   * The request path.
   * - For browser requests, this is the track's `url`
   * - For media requests, this is the track's `src` value
   * - For artwork requests, this is the track's `artwork` URL
   */
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

  // ─── CarPlay Options ──────────────────────────────────────────────────────────

  /**
   * Enable the "Up Next" button on the CarPlay Now Playing screen.
   *
   * When enabled, tapping "Up Next" shows the current playback queue,
   * allowing users to see upcoming tracks and jump to a specific position.
   *
   * The button is automatically hidden when the queue has only one track.
   *
   * @default true
   * @platform ios
   */
  carPlayUpNextButton?: boolean

  /**
   * Configure up to 5 buttons on the CarPlay Now Playing screen. These buttons
   * are arranged using the array order from left to right.
   *
   * @example
   * ```typescript
   * carPlayNowPlayingButtons: ['repeat']
   * ```
   *
   * @default []
   * @platform ios
   */
  carPlayNowPlayingButtons?: CarPlayNowPlayingButton[]

  /**
   * Configure the playback rates available when using the `'playback-rate'` button
   * on CarPlay Now Playing screen.
   *
   * The button cycles through these rates in order. Values should be positive numbers
   * where 1.0 is normal speed.
   *
   * @example
   * ```typescript
   * // For audiobooks/podcasts
   * carPlayNowPlayingRates: [0.75, 1, 1.25, 1.5, 2.0]
   *
   * // For music (no slow speeds)
   * carPlayNowPlayingRates: [1, 1.25, 1.5, 2.0]
   * ```
   *
   * @default [1.0, 1.5, 2.0]
   * @platform ios
   */
  carPlayNowPlayingRates?: number[]

  /**
   * Callback to customize error messages for navigation errors.
   * Used by CarPlay and available via `useFormattedNavigationError()` for app UI.
   *
   * If not provided or returns undefined, default English messages are used.
   *
   * @example
   * ```typescript
   * formatNavigationError: (error) => ({
   *   title: t(`error.${error.code}`),
   *   message: error.code === 'http-error'
   *     ? t('error.httpMessage', { status: error.statusCode })
   *     : error.message
   * })
   * ```
   */
  formatNavigationError?: FormatNavigationErrorCallback
}

/**
 * Custom button types for CarPlay Now Playing screen.
 *
 * - `'shuffle'`: Shuffle button that toggles shuffle mode on/off
 * - `'repeat'`: Repeat button that cycles through off → track → queue → off
 * - `'favorite'`: Heart button to toggle favorite state of current track
 * - `'playback-rate'`: Playback speed button that cycles through rate options
 *
 * @platform ios
 */
export type CarPlayNowPlayingButton =
  | 'shuffle'
  | 'repeat'
  | 'favorite'
  | 'playback-rate'

/**
 * Formatted navigation error for display in UI.
 * Used by CarPlay/Android Auto and available via `useFormattedNavigationError()` for app UI.
 */
export type FormattedNavigationError = {
  /**
   * Title shown in the error action sheet header.
   *
   * Default values:
   * - `'content-not-found'`: "Content Not Found" (English)
   * - `'network-error'`: "Network Error" (English)
   * - `'http-error'`: System-localized status text (e.g., "Not Found", "Service Unavailable")
   * - `'callback-error'`: "Error" (English)
   * - `'unknown-error'`: "Error" (English)
   */
  title: string
  /**
   * Message body shown below the title in the error action sheet.
   *
   * Default value: `error.message`
   */
  message: string
}

/**
 * Parameters passed to the formatNavigationError callback.
 */
export type FormatNavigationErrorParams = {
  /** The navigation error that occurred */
  error: NavigationError
  /** The default formatted error (useful for selective overrides) */
  defaultFormatted: FormattedNavigationError
  /** The path that was being navigated to when the error occurred */
  path: string
}

/**
 * Callback to customize navigation error display.
 * Return localized title and message for error presentation.
 *
 * @param params - Object containing error details and context
 * @returns Display information for the error, or undefined to use defaults
 *
 * @example
 * ```typescript
 * // Override only specific error types or routes
 * formatNavigationError: ({ error, defaultFormatted, path }) => {
 *   // Custom message for local server routes
 *   if (error.code === 'network-error' && path.startsWith('/errors')) {
 *     return {
 *       title: 'Server Not Running',
 *       message: 'Start the local server with: yarn server'
 *     }
 *   }
 *   // Use default for other errors
 *   return defaultFormatted
 * }
 * ```
 */
export type FormatNavigationErrorCallback = (
  params: FormatNavigationErrorParams
) => FormattedNavigationError | undefined
