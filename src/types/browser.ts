import type { NavigationError } from '../features/errors'
import type { ResolvedTrack, Track } from './browser-nodes.ts'

/**
 * Event fired when a track is about to be loaded via navigateTrack.
 */
export interface TrackLoadEvent {
  /** The track that will be loaded */
  track: Track
  /** The resolved queue of tracks */
  queue: Track[]
  /** The index of the track in the queue */
  startIndex: number
}

/**
 * Callback for handling track load events.
 * When set on BrowserConfiguration, navigateTrack() will call this handler
 * instead of auto-loading/playing the track. The native side awaits completion
 * before proceeding (e.g., showing Now Playing in CarPlay).
 */
export type HandleTrackLoadCallback = (event: TrackLoadEvent) => Promise<void>

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
) => BrowseResult | Promise<BrowseResult>

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
// Sync and async transforms are SEPARATE fields (`transform` / `transformSync`),
// never a `T | Promise<T>` union. A union lowers to a Nitro `variant<Struct,
// Promise<Struct>>`, and an all-optional struct's `canConvert` also accepts a
// Promise — so the async case is silently misread as an empty config. Two
// single-typed fields keep both paths unambiguous, and the sync field allocates
// no Promise. When both are set they run as a pipeline: async first, then sync.
export type RequestConfigTransformer = (
  request: RequestConfig,
  routeParams?: Record<string, string>
) => Promise<RequestConfig>
export type RequestConfigTransformerSync = (
  request: RequestConfig,
  routeParams?: Record<string, string>
) => RequestConfig

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
 * Context for image loading requests.
 * Provides pixel dimensions and appearance info from Android Auto/CarPlay.
 *
 * @example
 * ```typescript
 * // Use transform for custom URL manipulation
 * transform: async ({ request, context }) => ({
 *   ...request,
 *   query: {
 *     ...request.query,
 *     variant: context?.width && context.width < 200 ? 'thumb' : 'full'
 *   }
 * })
 *
 * // Or use imageQueryParams for simple declarative mapping
 * artwork: {
 *   imageQueryParams: { width: 'w', height: 'h' }
 * }
 * ```
 */
export interface ImageContext {
  /**
   * Requested image width in pixels.
   * Only provided when the display size is known (e.g., CarPlay, Android Auto, Now Playing).
   * Undefined at browse-time when display size is unknown.
   */
  width?: number
  /**
   * Requested image height in pixels.
   * Only provided when the display size is known (e.g., CarPlay, Android Auto, Now Playing).
   * Undefined at browse-time when display size is unknown.
   */
  height?: number
}

/**
 * Parameters for the media request transform callback.
 */
export interface MediaTransformParams {
  /** The merged request configuration to transform */
  request: RequestConfig
  /** Optional image context with size hints from Android Auto/CarPlay */
  context?: ImageContext
}

/**
 * Transform callback for media/artwork requests.
 * Unlike the route-based RequestConfigTransformer, this receives ImageContext
 * for size-aware transformations instead of route parameters.
 *
 * Use this when your CDN uses named variants or size presets. For simple
 * pixel-based query params, use `imageQueryParams` instead.
 *
 * @param params - The transform parameters containing request and optional context
 * @returns Modified request configuration
 *
 * @example
 * ```typescript
 * artwork: {
 *   transform: async ({ request, context }) => ({
 *     ...request,
 *     query: {
 *       ...request.query,
 *       // Use semantic size for CDN variant selection
 *       variant: context?.width && context.width < 200 ? 'thumb' : 'full',
 *       sig: await signUrl(request.path)
 *     }
 *   })
 * }
 * ```
 */
// Split sync/async — see RequestConfigTransformer. Set `transform` and/or `transformSync`.
export type MediaRequestConfigTransformer = (
  params: MediaTransformParams
) => Promise<RequestConfig>
export type MediaRequestConfigTransformerSync = (
  params: MediaTransformParams
) => RequestConfig

/**
 * Request configuration that supports async transformation.
 * Extends RequestConfig with a transform callback for dynamic request modification.
 *
 * The transform callback receives the merged request config and can modify it
 * before the request is made. This is useful for adding dynamic headers,
 * signing URLs, or other request-time modifications.
 *
 * Note: when a layer provides a transform, the layer's other static fields
 * are NOT merged — the transform receives the merged config from the layers
 * below it, and its return value replaces that config entirely. Spread the
 * incoming request (`{ ...request, ... }`) to keep its fields.
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
  /** Async per-request transform. When both are set, runs BEFORE `transformSync`. */
  transform?: RequestConfigTransformer
  /** Sync per-request transform (no Promise allocation). When both are set, runs AFTER `transform`. */
  transformSync?: RequestConfigTransformerSync
}

/**
 * Lazily builds the config for a `request` or `browse` layer. Reach for it when
 * the config depends on a value that changes now and then — a base URL, a
 * locale — but not on every request.
 *
 * How it differs from a `transform`: **a resolver runs once and its result is
 * cached**, then reused for every browse/search/media/artwork request — whereas a
 * `transform` runs on *every* request. The resolver re-runs only when you call
 * `invalidateAllContent()`, so call that after (say) an environment or locale
 * switch to pick up the new value.
 *
 * Return the config directly — no `async`/Promise needed in the common case — or
 * a `Promise` when you must await (e.g. fetching a token). The cached config
 * flows through the normal layering, so its `query` merges additively and its
 * `baseUrl` overrides, exactly like a static config; it may also include a
 * `transform` if you additionally need genuine per-request logic.
 *
 * @example
 * ```typescript
 * configureBrowser({
 *   // Read once and cached; re-read after the next invalidateAllContent().
 *   request: () => ({ baseUrl: currentBaseUrl() }),
 *   // Browse-only locale param, merged into every browse request's query.
 *   browse: () => ({ query: { hl: currentLocale() } }),
 * })
 *
 * // Async resolver (e.g. awaiting a token) — still runs once, then cached:
 * request: async () => ({ headers: { authorization: await freshToken() } })
 * ```
 */
export type RequestConfigResolver = () =>
  | TransformableRequestConfig
  | Promise<TransformableRequestConfig>

/**
 * Configuration for artwork image requests
 *
 * ## Configuration Hierarchy
 *
 * When a request is made, configs are merged in this order (later overrides earlier):
 * 1. `request` (base config) - shared settings like user agent, common headers
 * 2. `artwork` config - resource-specific settings
 * 3. `resolve(track)` result - per-track overrides (if provided)
 * 4. `imageQueryParams` - automatic size query param injection (if configured)
 * 5. `transform(request, context)` result - final modifications (if provided)
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
 * **Size-aware transformation (for artwork):**
 * ```typescript
 * artwork: {
 *   baseUrl: 'https://images.cdn.example.com',
 *   transform: async ({ request, context }) => ({
 *     ...request,
 *     query: {
 *       ...request.query,
 *       w: context?.width ? String(context.width) : '600',
 *       sig: await signUrl(request.path)
 *     }
 *   })
 * }
 * ```
 *
 * **Simple size params (declarative alternative):**
 * ```typescript
 * artwork: {
 *   baseUrl: 'https://images.cdn.example.com',
 *   imageQueryParams: { width: 'w', height: 'h' }
 * }
 * ```
 *
 * @see BrowserConfiguration.media - Audio stream configuration
 * @see BrowserConfiguration.artwork - Image/artwork configuration
 */

/**
 * Query parameter names for automatic context injection from CarPlay/Android Auto.
 * Maps ImageContext fields to query parameter names for your CDN.
 */
export interface ImageQueryParams {
  /** Query parameter name for width (e.g., 'w', 'width', 'size') */
  width?: string
  /** Query parameter name for height (e.g., 'h', 'height'). If omitted, only width is added. */
  height?: string
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
   * track metadata (id, artist, album, src, etc.).
   *
   * The returned config is merged with base configs, then passed to
   * `transform` if provided.
   *
   * @param track - The track being requested
   * @returns Request configuration for this specific track (sync or async)
   *
   * @example
   * ```typescript
   * resolve: async (track) => ({
   *   path: `/audio/${track.artist}/${track.album}/${track.src}`,
   *   query: { quality: 'high' }
   * })
   * ```
   */
  /** Async per-track resolution. When both are set, runs (merged) BEFORE `resolveSync`. */
  resolve?: (track: Track) => Promise<RequestConfig>
  /** Sync per-track resolution (no Promise allocation). When both are set, runs (merged) AFTER `resolve`. */
  resolveSync?: (track: Track) => RequestConfig
}

export interface ArtworkRequestConfig extends RequestConfig {
  /**
   * Per-track request resolution callback.
   *
   * Called for each track to generate the request configuration based on
   * track metadata (id, artist, album, src, etc.).
   *
   * The returned config is merged with base configs, then passed to
   * `transform` if provided.
   *
   * @param track - The track being requested
   * @returns Request configuration for this specific track
   *
   * @example
   * ```typescript
   * artwork: {
   *   resolve: async (track) => ({
   *     path: `/covers/${track.artist}/${track.album}.jpg`,
   *     query: { quality: 'high' }
   *   })
   * }
   * ```
   */
  /** Async per-track resolution. When both are set, runs (merged) BEFORE `resolveSync`. */
  resolve?: (track: Track) => Promise<RequestConfig>
  /** Sync per-track resolution (no Promise allocation). When both are set, runs (merged) AFTER `resolve`. */
  resolveSync?: (track: Track) => RequestConfig

  /**
   * Final transformation callback for media/artwork requests.
   *
   * Called after `resolve` (if provided) with the merged request config.
   * Receives optional ImageContext with size hints from Android Auto/CarPlay.
   *
   * Use this for:
   * - Adding size query params dynamically
   * - URL signing
   * - Adding authentication tokens
   *
   * @param request - The merged request configuration
   * @param context - Optional image context with size hints
   * @returns Modified request configuration
   *
   * @example
   * ```typescript
   * artwork: {
   *   transform: async ({ request, context }) => ({
   *     ...request,
   *     query: {
   *       ...request.query,
   *       w: context?.width ? String(context.width) : '600',
   *       sig: await signUrl(request.path)
   *     }
   *   })
   * }
   * ```
   */
  /** Async final transform. When both are set, runs BEFORE `transformSync`. */
  transform?: MediaRequestConfigTransformer
  /** Sync final transform (no Promise allocation). When both are set, runs AFTER `transform`. */
  transformSync?: MediaRequestConfigTransformerSync

  /**
   * Query parameter names for automatic context injection from CarPlay/Android Auto.
   *
   * When configured, the image context (size, color scheme) from CarPlay/Android Auto
   * is automatically added as query parameters to artwork URLs.
   *
   * This is a simpler alternative to using `transform` for context-aware URLs.
   *
   * @example
   * ```typescript
   * // CDN expects ?w=400&h=400
   * artwork: {
   *   baseUrl: 'https://images.cdn.com',
   *   imageQueryParams: { width: 'w', height: 'h' }
   * }
   *
   * // CDN expects single size param ?size=400
   * artwork: {
   *   baseUrl: 'https://images.cdn.com',
   *   imageQueryParams: { width: 'size' }
   * }
   * ```
   */
  imageQueryParams?: ImageQueryParams

}

/**
 * Source for a browse container's contents.
 *
 * **Response shape (HTTP / `TransformableRequestConfig`):** the endpoint must
 * return a single **page object** — a {@link ResolvedTrack}
 * (`{ title, url?, children: Track[] }`). The `children` array holds the rows
 * shown for the container; each child is a playable leaf (`src`) or a navigable
 * sub-container (`url`). A callback / static `ResolvedTrack` returns the same
 * page object directly.
 *
 * {@link SearchSource} and {@link TabsSource} HTTP endpoints return this same
 * page shape; only the meaning of `children` differs (results / tabs).
 */
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
  artwork?: ArtworkRequestConfig
}

export type TabsSourceCallback = () => Track[] | Promise<Track[]>
/**
 * Tab source configuration for navigation tabs.
 *
 * When using API configuration (TransformableRequestConfig), the request path
 * defaults to '/' and the endpoint must return a page object
 * `{ title?, children: Track[] }` whose `children` are the tabs — the same
 * shape a browse endpoint returns. Callback/static sources provide `Track[]`
 * directly.
 */
export type TabsSource =
  | Track[]
  | TabsSourceCallback
  | TransformableRequestConfig

/**
 * Search source configuration for handling search requests.
 *
 * **Response shape (HTTP / `TransformableRequestConfig`):** the endpoint must
 * return a page object — `{ title?, children: Track[] }` — whose `children`
 * are the result rows, the same shape a browse endpoint returns. (The web
 * implementation additionally accepts a bare `Track[]` for back-compat;
 * iOS/Android do not.) Callback sources return `Track[]` directly.
 *
 * @see BrowserConfiguration.search
 */
export type SearchSource = SearchSourceCallback | TransformableRequestConfig

/**
 * How ids passed to {@link AudioBrowser.setFavorites} are matched against a
 * track's `src` to hydrate its `favorited` flag.
 *
 * - `'exact'`: the id must equal `src`.
 * - `'partial'`: the id matches if it appears as a complete path segment within
 *   `src` (delimited by `/`, `?`, `#`, or the string boundaries). Useful when
 *   favorites are stored as a stable identifier that is embedded in — but not
 *   equal to — the playable `src` URL.
 *
 * @example
 * ```text
 * setFavorites(['abc123'])  +  track.src = '/stream/jazz-fm/abc123'
 *
 *   'exact'    'abc123' === '/stream/jazz-fm/abc123'   → not favorited
 *   'partial'  'abc123' is the last segment of the src → favorited
 *
 * setFavorites(['https://cdn.example.com/jazz-fm/abc123.mp3'])
 *
 *   'exact'    id === src                              → favorited
 *   'partial'  id is a full segment of src             → favorited
 * ```
 */
export type FavoritesMatchMode = 'exact' | 'partial'

/**
 * Object form of the `favorite` capability — enables favoriting with an explicit
 * {@link FavoritesMatchMode}. (A bare `true` is shorthand for `{ match: 'exact' }`.)
 */
export interface FavoriteConfig {
  match: FavoritesMatchMode
}

export type BrowserConfiguration = {
  /**
   * Initial navigation path. Setting this triggers initial navigation to the
   * specified path. When unset, the first tab's URL is used; when there are
   * no tabs either, `/`.
   */
  path?: string | undefined

  // ─── Request Defaults (applied to all requests) ────────────────────────────

  /**
   * Shared request settings applied to every HTTP request (browse, search,
   * media, artwork). Layered before the per-kind config and (for browse) the
   * route — so request → `<kind>` → route. Specific configs override these
   * defaults.
   *
   * Either a static {@link TransformableRequestConfig} (its optional `transform`
   * runs per request), or a {@link RequestConfigResolver} thunk resolved once per
   * content generation and re-resolved on `invalidateAllContent()`. Reach for the
   * resolver when a value changes rarely (a base URL, an auth host) so it is read
   * once per generation and merged natively — rather than recomputed on every
   * browse/search/media/artwork request via a `transform`.
   */
  request?: TransformableRequestConfig | RequestConfigResolver

  // ─── Per-kind request configuration ─────────────────────────────────────────

  /**
   * Request shaping applied to every browse request (all routes, including the
   * implicit default), layered between `request` and the matched route:
   * `request` → `browse` → route. This is the browse-kind analogue of `media`
   * and `artwork` — the place for browse-only concerns (e.g. a content-type
   * marker query, or a locale param) that should not leak onto media/artwork.
   *
   * A browse path with no matching `routes` entry is fetched using
   * `request` + `browse` applied to the path, so this also defines the default
   * browse behaviour. Register a `routes['*']` entry only to override that
   * default with a callback / static / bespoke config.
   *
   * Like `request`, this may be a static {@link TransformableRequestConfig} or a
   * {@link RequestConfigResolver} thunk (resolved once per content generation) —
   * e.g. a locale query param that only changes when `invalidateAllContent()` is
   * called. A resolver's `query` is merged additively into each browse request's
   * query, exactly as a static `query` would be.
   */
  browse?: TransformableRequestConfig | RequestConfigResolver

  /**
   * Media/audio stream request configuration.
   *
   * Unlike `request`/`browse`, this does not accept a resolver thunk. For
   * values that change at runtime, use the shared `request` resolver (it
   * applies to media requests too), a per-track `resolve`, or a `transform`.
   */
  media?: MediaRequestConfig

  /**
   * Configuration for artwork/image requests.
   * Used to transform artwork URLs for CDNs that require different authentication tokens,
   * base URLs, or query parameters than audio requests.
   *
   * Artwork URLs are transformed when tracks are processed (before being passed to media controllers
   * like Android Auto). This is different from media requests which are transformed at playback time.
   *
   * Headers ARE applied to artwork requests: the library fetches CarPlay and
   * Android Auto images in-process, and in-app consumers receive them via
   * `artworkSource` (which carries the headers for React Native's `<Image>`).
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
  artwork?: ArtworkRequestConfig

  /**
   * Artwork configuration for the NOW-PLAYING surface only (lock screen / CarPlay /
   * Android Auto now-playing) — distinct from `artwork`, which configures browse-list
   * thumbnails. When set, the now-playing artwork is resolved from THIS config instead of
   * `artwork`; browse lists never read it, so they're unaffected. When unset, now-playing
   * falls back to `artwork` / the track's own `artwork`.
   *
   * Being a full `RequestConfig`, it supports `path`, `query`, and `headers`. The token
   * `{id}` in any of those values is replaced with the track's `id` during resolution, and
   * the result flows through the shared `request` layer (so a relative path gets `baseUrl`
   * prepended). For logic that can't be expressed as a template, use `resolve(track)`.
   *
   * The `{id}` templating is specific to `nowPlayingArtwork` — static values
   * in other configs are not templated. Not implemented by the web
   * implementation.
   *
   * @example
   * // 302-redirect endpoint keyed by the track id:
   * nowPlayingArtwork: { path: '/artwork/{id}' }
   */
  nowPlayingArtwork?: ArtworkRequestConfig

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
   * These query-param keys are fixed (not configurable). If your endpoint
   * expects different names, rename them in `transform` — it receives the params
   * already on `request.query`, e.g. `query: { search: request.query?.q }`.
   *
   * Response shape: the endpoint must return a page object
   * `{ children: Track[] }` — see {@link SearchSource}.
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
   * Route-specific configurations. Maps URL path patterns to browse sources.
   *
   * ## Matching
   *
   * Patterns match on **exact segment count** — `/artists` does NOT match
   * `/artists/123`. Segments may be:
   * - a constant: `/favorites`
   * - a parameter: `/albums/{id}` — captured into `routeParams.id` and passed
   *   to callbacks and transforms
   * - a single-segment wildcard: `*`
   * - a tail wildcard: `/files/**` — matches any depth; the remainder is
   *   captured into `routeParams.tail`
   *
   * When several patterns match, the most specific wins
   * (constants > parameters > wildcards > tail wildcard).
   *
   * ## Defaults and reserved keys
   *
   * A path matching no route is fetched over HTTP via the `request` →
   * `browse` layers with the path applied — most APIs only need explicit
   * routes for exceptions. The special key `'*'` overrides that default with
   * its own source. Keys starting with `__` are reserved for internal use.
   *
   * Note: a static `path` on a route's request config does not rewrite the
   * request path (the navigated path is used); remap paths in a `transform`,
   * which receives `routeParams` as its second argument.
   *
   * Values can be a `BrowserSource` (callback, request config, or static page
   * object) or a `RouteConfig` with per-route `media`/`artwork` overrides.
   *
   * @example
   * ```typescript
   * routes: {
   *   '/favorites': async () => getFavoritesPage(),
   *   '/albums/{id}': async ({ routeParams }) => fetchAlbumPage(routeParams?.id),
   *   '/premium': {
   *     browse: { baseUrl: 'https://premium-api.example.com' },
   *     artwork: { baseUrl: 'https://premium-images.example.com' }
   *   },
   *   // Custom default for anything no other route matches:
   *   '*': { baseUrl: 'https://api.example.com' }
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
   * Custom handler for track load events.
   * When set, navigateTrack() will call this handler instead of auto-loading/playing the track.
   * Pass undefined to restore default behavior.
   *
   * @param event - The track load event containing the track, queue, and startIndex
   */
  handleTrackLoad?: HandleTrackLoadCallback

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
   * Title shown (as the list's centered empty state) on CarPlay screens whose
   * content is still loading — browse destinations while they resolve, and the
   * startup screen while tabs load. Supply your app's localized "Loading…"
   * string. On iOS 18.4+ the system loading spinner is shown instead.
   *
   * When unset, loading screens are left blank (apart from the spinner on
   * iOS 18.4+) rather than showing un-localized copy.
   *
   * @example
   * ```typescript
   * carPlayLoadingTitle: t('loading')
   * ```
   *
   * @platform ios
   */
  carPlayLoadingTitle?: string

  /**
   * Called when the album line on the CarPlay Now Playing screen is tapped
   * and the active track has no {@link Track.albumUrl}. Return a browse path
   * to navigate the CarPlay browse stack there, or `undefined` if the tap was
   * handled (or should do nothing).
   *
   * The album line is tappable whenever the active track has an `albumUrl`
   * or this callback is configured.
   *
   * @example
   * ```typescript
   * resolveAlbumUrl: (track) =>
   *   track.album ? `/album/${slugify(track.album)}` : undefined
   * ```
   *
   * @platform ios
   */
  resolveAlbumUrl?: ResolveAlbumUrlCallback

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
 * Position of the "Ask Siri to Play Audio" assistant cell on a CarPlay list template.
 *
 * @platform ios
 */
export type CarPlaySiriListButtonPosition = 'top' | 'bottom'

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
   * Optional second line — the body in the error action sheet, the subtitle in
   * the browse error/empty view. Omit it to show a title only.
   *
   * Default value: `error.message` (omitted when that is empty).
   */
  message?: string
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

/**
 * Maps the tapped active track to a browse path for the CarPlay album line,
 * or `undefined` to do nothing. See `resolveAlbumUrl`.
 */
export type ResolveAlbumUrlCallback = (
  track: Track
) => string | undefined
