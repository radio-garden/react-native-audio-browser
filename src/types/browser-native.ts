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

  // Request defaults
  request?: TransformableRequestConfig

  // Global media/artwork config (applied when route doesn't override)
  media?: MediaRequestConfig
  artwork?: ArtworkRequestConfig

  // Routes as array - includes:
  // - Explicit routes from config.routes
  // - Root browse as __default__ entry
  // - Tabs as __tabs__ entry (returns ResolvedTrack with children for navigation tabs)
  // - Search as __search__ entry (has searchCallback or searchConfig)
  routes?: NativeRouteEntry[]

  // Behavior
  singleTrack?: boolean
  androidControllerOfflineError?: boolean

  // CarPlay options
  carPlayUpNextButton?: boolean
  carPlayNowPlayingButtons?: CarPlayNowPlayingButton[]
  formatNavigationError?: FormatNavigationErrorCallback
}
