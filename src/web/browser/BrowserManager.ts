import type { Track, ResolvedTrack } from '../../types'
import type { NativeBrowserConfiguration } from '../../types/browser-native'
import type { NavigationErrorType } from '../../features'
import { SimpleRouter } from '../SimpleRouter'
import type { HttpClient } from '../http/HttpClient'
import type { FavoriteManager } from './FavoriteManager'
import type { NavigationErrorManager } from './NavigationErrorManager'
import { RequestConfigBuilder } from '../http/RequestConfigBuilder'
import { BrowserPathHelper } from '../util/BrowserPathHelper'

/**
 * Manages browser navigation, route resolution, and content loading.
 * Coordinates between HTTP client, favorite manager, and error manager.
 * Matches Android's BrowserManager architecture.
 */
export class BrowserManager {
  private _path: string | undefined
  private _tabs: Track[] | undefined
  private _content: ResolvedTrack | undefined
  private _configuration: NativeBrowserConfiguration = {}
  private router = new SimpleRouter()

  // Navigation tracking to prevent race conditions (matches Android's @Volatile currentNavigationId)
  private currentNavigationId = 0

  // Queue expansion tracking (matches Android's queueSourcePath)
  private _queueSourcePath: string | undefined

  // Event callbacks
  onPathChanged: (path: string) => void = () => {}
  onContentChanged: (content: ResolvedTrack | undefined) => void = () => {}
  onTabsChanged: (tabs: Track[]) => void = () => {}

  constructor(
    private httpClient: HttpClient,
    private favoriteManager: FavoriteManager,
    private navigationErrorManager: NavigationErrorManager
  ) {}

  /**
   * Handles navigation errors by extracting error details and setting the navigation error.
   * Extracts code, message, and statusCode from typed errors, or falls back to generic network error.
   */
  private handleNavigationError(error: unknown, path: string): void {
    if (
      error &&
      typeof error === 'object' &&
      'code' in error &&
      'message' in error &&
      typeof error.message === 'string'
    ) {
      const navError = error as { code: NavigationErrorType; message: string; statusCode?: number }
      this.navigationErrorManager.setNavigationError(navError.code, navError.message, path, navError.statusCode)
    } else {
      this.navigationErrorManager.setNavigationError('network-error', 'Failed to load content', path)
    }
  }

  /**
   * Gets the current navigation path.
   */
  get path(): string | undefined {
    return this._path
  }

  /**
   * Sets the navigation path and triggers navigation.
   */
  set path(value: string | undefined) {
    if (this.hasValidConfiguration()) {
      this.navigationErrorManager.clearNavigationError()
      const pathToNavigate = value ?? this._configuration.path ?? this.getDefaultPath()
      if (pathToNavigate) {
        void this.navigate(pathToNavigate)
      }
    }
  }

  /**
   * Gets the current tabs.
   */
  get tabs(): Track[] | undefined {
    return this._tabs
  }

  /**
   * Gets the current content.
   */
  get content(): ResolvedTrack | undefined {
    return this._content
  }

  /**
   * Gets the current browser configuration.
   */
  get configuration(): NativeBrowserConfiguration {
    return this._configuration
  }

  /**
   * Gets the current queue source path.
   * Used to track where the current playback queue came from.
   */
  get queueSourcePath(): string | undefined {
    return this._queueSourcePath
  }

  /**
   * Sets the queue source path.
   */
  set queueSourcePath(value: string | undefined) {
    this._queueSourcePath = value
  }

  /**
   * Sets the browser configuration and triggers initial navigation.
   */
  set configuration(value: NativeBrowserConfiguration) {
    this._configuration = value
    this.navigationErrorManager.clearNavigationError()
    this.navigationErrorManager.setFormatCallback(value.formatNavigationError)

    // Update HTTP client base config
    if (value.request) {
      this.httpClient.setBaseRequestConfig(value.request)
    }

    // Determine initial path
    let initialPath = value.path

    // If no path specified, try to get first tab URL
    if (!initialPath) {
      const tabsRoute = value.routes?.find(r => r.path === '__tabs__')
      if (tabsRoute?.browseStatic?.children?.[0]?.url) {
        initialPath = tabsRoute.browseStatic.children[0].url
      } else {
        initialPath = '/'
      }
    }

    if (initialPath) {
      void this.navigate(initialPath)
    }
  }

  /**
   * Navigates to a specific path.
   */
  async navigatePath(path: string): Promise<void> {
    this.navigationErrorManager.clearNavigationError()
    await this.navigate(path)
  }

  /**
   * Navigates to a track's URL.
   */
  async navigateTrack(track: Track): Promise<void> {
    this.navigationErrorManager.clearNavigationError()
    const url = track.url
    if (!url) {
      console.warn('Track has no URL to navigate to')
      return
    }
    await this.navigate(url)
  }

  /**
   * Notifies that content at a specific path has changed.
   * If the path is currently displayed, triggers a refresh.
   */
  notifyContentChanged(path: string): void {
    if (this._path === path) {
      void this.navigate(path)
    }
  }

  /**
   * Main navigation logic.
   * Resolves content for the given path and updates state.
   * Uses navigation ID to prevent race conditions (matches Android's BrowserManager.kt:586-607).
   */
  private async navigate(path: string): Promise<void> {
    // Increment navigation ID and capture for this navigation
    // This prevents stale responses from overwriting newer ones
    const navigationId = ++this.currentNavigationId

    try {
      // Update path and clear content immediately to show loading state
      this._path = path
      this._content = undefined
      this.onPathChanged(path)
      this.onContentChanged(undefined)

      // Resolve content for this path
      // Search paths use a separate code path that doesn't add contextual URLs
      // This matches Android where search() bypasses resolve() entirely
      const isSearchPath = path.startsWith(BrowserPathHelper.SEARCH_PATH_PREFIX)
      let content = isSearchPath
        ? await this.resolveSearchContent(path)
        : await this.resolveContent(path)

      // Check if this is still the current navigation before applying result
      if (navigationId !== this.currentNavigationId) {
        // A newer navigation started - discard this result
        return
      }

      // Transform tracks with src to add contextual URLs (matches Android behavior)
      // Android ALWAYS regenerates contextual URLs for tracks with src to reflect
      // current browsing context, enabling proper queue expansion.
      // Note: Search content is resolved via resolveSearchContent() which doesn't
      // reach this block because it returns directly above. This matches Android's
      // architecture where search() bypasses the resolve() contextual URL logic.
      // IMPORTANT: Create a shallow copy to avoid mutating the original config object
      // (e.g., browseStatic from routes). Without this, static route children would
      // accumulate contextual URLs, breaking search which reads from the same source.
      if (content?.children && !isSearchPath) {
        content = {
          ...content,
          children: content.children.map(track => {
            // If track has src, always add/update contextual URL with current path context
            // This matches Android's BrowserManager.kt:436-441
            if (track.src) {
              const contextualUrl = `${path}?__trackId=${encodeURIComponent(track.src)}`
              return { ...track, url: contextualUrl }
            }
            // Return a shallow copy in attempt to avoid mutating original config objects
            return { ...track }
          })
        }
      }

      // Hydrate favorites on all children
      if (content) {
        content = this.favoriteManager.hydrateChildren(content)
      }

      // Transform artwork URLs (populates artworkSource)
      if (content) {
        content = await this.transformArtworkForContent(content)
      }

      // Final check before applying result
      if (navigationId !== this.currentNavigationId) {
        return
      }

      this._content = content
      this.onContentChanged(content)

      // Query and update tabs if configuration has __tabs__ route
      const tabsRoute = this._configuration.routes?.find(r => r.path === '__tabs__')
      if (tabsRoute) {
        const tabs = await this.queryTabs()

        // Check again before applying tabs
        if (navigationId !== this.currentNavigationId) {
          return
        }

        this._tabs = tabs
        this.onTabsChanged(tabs)
      }
    } catch (error) {
      // Only apply error if this is still the current navigation
      if (navigationId !== this.currentNavigationId) {
        return
      }

      console.error('Navigation failed:', error)
      this._content = undefined
      this.onContentChanged(undefined)

      // Set navigation error using helper
      const message = error instanceof Error ? error.message : 'Unknown error'
      this.navigationErrorManager.setNavigationError('unknown-error', message)
    }
  }

  /**
   * Resolves search content from a search path.
   * Returns a ResolvedTrack with raw children (no contextual URLs).
   * Matches Android's BrowserManager.search() which bypasses resolve() entirely.
   *
   * @param searchPath The search path (format: /__search?q=query)
   * @returns ResolvedTrack containing search results as children
   */
  private async resolveSearchContent(searchPath: string): Promise<ResolvedTrack | undefined> {
    // Extract query from search path
    const queryMatch = searchPath.match(/[?&]q=([^&]*)/)
    if (!queryMatch) {
      console.warn('Invalid search path, missing query parameter:', searchPath)
      return undefined
    }
    const query = decodeURIComponent(queryMatch[1] ?? '')

    // Find __search__ route entry
    const searchRoute = this._configuration.routes?.find(r => r.path === '__search__')
    if (!searchRoute) {
      console.warn('No __search__ route configured')
      return undefined
    }

    let searchResults: Track[] = []

    // Handle callback-based search
    if (searchRoute.searchCallback) {
      searchResults = await searchRoute.searchCallback({ query })
    }
    // Handle request config-based search
    else if (searchRoute.searchConfig) {
      const searchQueryParams: Record<string, string> = { q: query }
      const requestConfig = this.httpClient.mergeRequestConfig(searchRoute.searchConfig, {
        query: searchQueryParams
      })

      try {
        const response = await this.httpClient.executeRequest(requestConfig)
        searchResults = Array.isArray(response) ? (response as Track[]) : []
      } catch (error) {
        console.error('Search failed:', error)
        return undefined
      }
    }

    // Create ResolvedTrack with raw children
    // Search callbacks should return fresh tracks without contextual URLs (like Android)
    return {
      url: searchPath,
      title: `Search: ${query}`,
      children: searchResults,
    }
  }

  /**
   * Transforms artwork URLs for content and its children.
   * Supports both static config and resolve/transform callbacks.
   * Matches Android's artwork URL transformation with full Track access.
   */
  private async transformArtworkForContent(content: ResolvedTrack): Promise<ResolvedTrack> {
    const artworkConfig = this._configuration.artwork
    if (!artworkConfig) {
      return content
    }

    // Transform parent artwork
    const parentArtworkSource = await RequestConfigBuilder.resolveArtworkSourceAsync(
      content,
      artworkConfig
    )

    // Transform children artwork
    let transformedChildren: Track[] | undefined
    if (content.children) {
      transformedChildren = await Promise.all(
        content.children.map(async track => {
          const artworkSource = await RequestConfigBuilder.resolveArtworkSourceAsync(
            track,
            artworkConfig
          )
          if (artworkSource && !track.artworkSource) {
            return { ...track, artworkSource }
          }
          return track
        })
      )
    }

    return {
      ...content,
      artworkSource: parentArtworkSource ?? content.artworkSource,
      children: transformedChildren ?? content.children,
    }
  }

  /**
   * Resolves content from a route using browseCallback, browseStatic, or browseConfig.
   * Single source of truth for route resolution logic.
   *
   * @param route The route configuration to resolve
   * @param path The path being navigated to
   * @param routeParams Extracted route parameters
   * @param errorContext Context string for error logging
   * @returns ResolvedTrack or undefined if resolution fails
   */
  private async resolveRouteContent(
    route: {
      browseCallback?: NativeBrowserConfiguration['routes'] extends (infer R)[] | undefined
        ? R extends { browseCallback?: infer C } ? C : never
        : never
      browseStatic?: ResolvedTrack
      browseConfig?: NativeBrowserConfiguration['routes'] extends (infer R)[] | undefined
        ? R extends { browseConfig?: infer C } ? C : never
        : never
    },
    path: string,
    routeParams: Record<string, string>,
    errorContext: string
  ): Promise<ResolvedTrack | undefined> {
    // Handle callback-based route
    if (route.browseCallback) {
      const result = await route.browseCallback({ path, routeParams })
      if ('error' in result) {
        console.error(`${errorContext} browse error:`, result.error)
        this.navigationErrorManager.setNavigationError(
          'callback-error',
          result.error,
          path
        )
        return undefined
      }
      return result
    }

    // Handle static ResolvedTrack route
    if (route.browseStatic) {
      return route.browseStatic
    }

    // Handle request config-based route
    if (route.browseConfig) {
      const requestConfig = this.httpClient.mergeRequestConfig(route.browseConfig, { path })
      try {
        const response = await this.httpClient.executeRequest(requestConfig)
        return response as ResolvedTrack
      } catch (error: unknown) {
        console.error(`Failed to resolve ${errorContext}:`, error)
        this.handleNavigationError(error, path)
        return undefined
      }
    }

    return undefined
  }

  /**
   * Resolves content for a specific path using configured routes.
   */
  private async resolveContent(path: string): Promise<ResolvedTrack | undefined> {
    const routes = this._configuration.routes
    if (!routes || routes.length === 0) {
      return undefined
    }

    // Convert routes array to record for SimpleRouter
    const routePatterns: Record<string, {
      browseCallback?: typeof routes[0]['browseCallback']
      browseConfig?: typeof routes[0]['browseConfig']
      browseStatic?: typeof routes[0]['browseStatic']
    }> = {}

    for (const route of routes) {
      // Skip special routes
      if (route.path.startsWith('__')) continue

      routePatterns[route.path] = {
        browseCallback: route.browseCallback,
        browseConfig: route.browseConfig,
        browseStatic: route.browseStatic,
      }
    }

    // Try to match route
    const match = this.router.findBestMatch(path, routePatterns)
    if (match) {
      const [matchedPattern, routeMatch] = match
      const matchedRoute = routes.find(r => r.path === matchedPattern)
      if (matchedRoute) {
        return this.resolveRouteContent(matchedRoute, path, routeMatch.params, 'Route')
      }
    }

    // Fall back to __default__ route
    const defaultRoute = routes.find(r => r.path === '__default__')
    if (defaultRoute) {
      return this.resolveRouteContent(defaultRoute, path, {}, 'Default route')
    }

    return undefined
  }

  /**
   * Queries tabs from the __tabs__ route.
   */
  private async queryTabs(): Promise<Track[]> {
    const tabsRoute = this._configuration.routes?.find(r => r.path === '__tabs__')
    if (!tabsRoute) {
      return []
    }

    const result = await this.resolveRouteContent(tabsRoute, '/', {}, 'Tabs')
    const tabs = result?.children ?? []

    // Transform artwork URLs on tabs
    return RequestConfigBuilder.transformTracksArtwork(tabs, this._configuration.artwork)
  }

  /**
   * Checks if the configuration has valid routes.
   */
  private hasValidConfiguration(): boolean {
    return this._configuration.routes !== undefined && this._configuration.routes.length > 0
  }

  /**
   * Expands a queue from a contextual URL.
   * Resolves the parent container to get all siblings.
   * Matches Android's BrowserManager.expandQueueFromContextualUrl() behavior.
   *
   * @param contextualUrl The contextual URL (format: /path?__trackId=trackSrc)
   * @returns Object with tracks and selectedIndex, or undefined if expansion fails
   */
  async expandQueueFromContextualUrl(
    contextualUrl: string
  ): Promise<{ tracks: Track[]; selectedIndex: number } | undefined> {
    const trackId = BrowserPathHelper.extractTrackId(contextualUrl)
    if (!trackId) return undefined

    try {
      // Resolve the parent container to get all siblings
      const parentPath = BrowserPathHelper.stripTrackId(contextualUrl)
      const parentResolvedTrack = await this.resolveContent(parentPath)
      const children = parentResolvedTrack?.children

      if (!children || children.length === 0) {
        console.warn('Parent has no children, cannot expand queue')
        return undefined
      }

      // Filter to only playable tracks (tracks with src)
      const playableTracks = children.filter(track => track.src != null)

      if (playableTracks.length === 0) {
        console.warn('Parent has no playable tracks, cannot expand queue')
        return undefined
      }

      // Find the index of the selected track in the playable tracks array
      const selectedIndex = playableTracks.findIndex(track => track.src === trackId)

      if (selectedIndex < 0) {
        console.warn(`Track with src='${trackId}' not found in playable children`)
        return undefined
      }

      // Check singleTrack setting - if true, return only the selected track
      if (this._configuration.singleTrack) {
        return {
          tracks: [playableTracks[selectedIndex]!],
          selectedIndex: 0,
        }
      }
      return { tracks: playableTracks, selectedIndex }
    } catch (error) {
      console.error(`Error expanding queue from contextual URL: ${contextualUrl}`, error)
      return undefined
    }
  }

  /**
   * Resolves media items for playback with queue expansion support.
   * Handles search results, contextual URLs, and fallback to single track.
   * Matches Android's BrowserManager.resolveMediaItemsForPlayback() behavior.
   *
   * @param tracks The tracks to resolve
   * @param startIndex The starting index
   * @param startPositionMs The starting position in milliseconds
   * @param searchQuery Optional search query that generated these tracks
   * @returns Object with expanded tracks and starting index
   */
  async resolveMediaItemsForPlayback(
    tracks: Track[],
    startIndex: number,
    startPositionMs: number,
    searchQuery?: string
  ): Promise<{ tracks: Track[]; startIndex: number; startPositionMs: number }> {
    // Single track: check for search context or contextual URL
    if (tracks.length === 1) {
      const track = tracks[0]!
      const trackUrl = track.url

      // If search query present, expand search results
      if (searchQuery) {
        // Execute search (will hit cache if already performed)
        const searchResults = await this.resolveContent(
          BrowserPathHelper.createSearchPath(searchQuery)
        )
        const searchTracks = searchResults?.children

        if (searchTracks && searchTracks.length > 0) {
          // Find the selected track in search results
          const selectedIdx = searchTracks.findIndex(
            t => t.url === trackUrl || t.src === track.src
          )

          if (selectedIdx >= 0) {
            return {
              tracks: searchTracks,
              startIndex: selectedIdx,
              startPositionMs,
            }
          }
        }
      }

      // Check if contextual URL - expand from parent
      // Note: The queueSourcePath optimization is handled by the caller (NativeAudioBrowser.navigateTrackAsync)
      // which has access to the existing queue and can skip to the track directly.
      // This matches Android where MediaSessionCallback handles the optimization before calling
      // BrowserManager.resolveMediaItemsForPlayback.
      if (trackUrl && BrowserPathHelper.isContextual(trackUrl)) {
        const parentPath = BrowserPathHelper.stripTrackId(trackUrl)
        const expanded = await this.expandQueueFromContextualUrl(trackUrl)

        if (expanded) {
          // Store source path for optimization by caller on next invocation
          this._queueSourcePath = parentPath
          return {
            tracks: expanded.tracks,
            startIndex: expanded.selectedIndex,
            startPositionMs,
          }
        }
      }
    }

    // No expansion - use tracks as-is
    return { tracks, startIndex, startPositionMs }
  }

  /**
   * Gets the default navigation path (first tab URL or '/').
   */
  private getDefaultPath(): string {
    // Try to get first tab as default path
    if (this._tabs && this._tabs.length > 0) {
      const firstTab = this._tabs[0]
      return firstTab?.url ?? '/'
    }
    return '/'
  }
}
