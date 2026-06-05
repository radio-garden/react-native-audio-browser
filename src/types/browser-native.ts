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
  CarPlayNowPlayingButton,
  FormatNavigationErrorCallback,
  HandleTrackLoadCallback,
  MediaRequestConfig,
  SearchSourceCallback,
  TransformableRequestConfig
} from './browser'
import type { ResolvedTrack } from './browser-nodes'

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

  // Request defaults (applied to every request: browse, search, media, artwork)
  request?: TransformableRequestConfig

  // Per-kind request config, layered request → <kind> → route.
  browse?: TransformableRequestConfig
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
  carPlayUpNextButton?: boolean
  carPlayNowPlayingButtons?: CarPlayNowPlayingButton[]
  formatNavigationError?: FormatNavigationErrorCallback
}
