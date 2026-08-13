/**
 * Native Configuration Types
 *
 * These types are used internally by the native bridge. They flatten union types
 * into separate optional properties to reduce generated code complexity.
 * Users should use BrowserConfiguration, not these types directly.
 */

import type {
  ArtworkRequestConfig,
  BrowserSourceCallback,
  ResolveAlbumPathCallback,
  FormatNavigationErrorCallback,
  HandleTrackLoadCallback,
  MediaRequestConfig,
  SearchSourceCallback,
  TransformableRequestConfig
} from './browser'
import type { ResolvedTrack } from './browser-nodes'

// Native resolvers are Promise-only. The public `request`/`browse` resolver may
// return its config sync OR async; `toNativeConfig` wraps it in Promise.resolve so
// the bridge never sees a `T | Promise<T>` variant (which would misread the async
// case). Resolvers run once per content generation, so the wrap is free.
type NativeRequestConfigResolver = () => Promise<TransformableRequestConfig>

/**
 * Flattened route entry for native bridge.
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
  artwork?: ArtworkRequestConfig
}

/**
 * Flattened browser configuration for native bridge.
 * Converts union types to separate optional properties for simpler native code generation.
 */
export interface NativeBrowserConfiguration {
  path?: string

  // Request defaults (applied to every request: browse, search, media, artwork).
  // The union `TransformableRequestConfig | RequestConfigResolver` from the public
  // API is lowered here into two sibling fields (mirrors browseCallback/browseConfig).
  request?: TransformableRequestConfig
  requestResolver?: NativeRequestConfigResolver

  // Per-kind request config, layered request → <kind> → route.
  browse?: TransformableRequestConfig
  browseResolver?: NativeRequestConfigResolver
  media?: MediaRequestConfig
  artwork?: ArtworkRequestConfig
  nowPlayingArtwork?: ArtworkRequestConfig

  // Routes as array - includes:
  // - Explicit routes from config.routes
  // - The '*' route (if any) as the __default__ entry (custom default override)
  // - Tabs as __tabs__ entry (returns ResolvedTrack with children for navigation tabs)
  // - Search as __search__ entry (has searchCallback or searchConfig)
  // A browse path matching none of these is fetched via request + browse + path.
  routes?: NativeRouteEntry[]

  // Behavior
  singleTrack?: boolean
  handleTrackLoad?: HandleTrackLoadCallback
  androidControllerOfflineError?: boolean

  // CarPlay options
  carPlayLoadingTitle?: string
  resolveAlbumPath?: ResolveAlbumPathCallback
  formatNavigationError?: FormatNavigationErrorCallback
}
