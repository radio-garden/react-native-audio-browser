import type { NavigationErrorType } from '../../features'
import type {
  Track,
  ResolvedTrack,
  TransformableRequestConfig
} from '../../types'
import type { NativeBrowserConfiguration } from '../../types/browser-native'
import type { HttpClient } from '../http/HttpClient'
import type { FavoriteManager } from './FavoriteManager'
import type { NavigationErrorManager } from './NavigationErrorManager'
import { getTrackIdentity } from '../../utils/getTrackIdentity'
import { assertedNotNullish } from '../../utils/validation'
import { RequestConfigBuilder } from '../http/RequestConfigBuilder'
import { SimpleRouter } from '../SimpleRouter'
import { BrowserPathHelper } from '../util/BrowserPathHelper'
import { assertBrowsePageShape } from './assertBrowsePageShape'
import { parseSearchResponse } from './parseSearchResponse'

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

  // Layer resolution: any present request/browse resolver is invoked once per
  // content generation, and the resolved TransformableRequestConfig is cached
  // and fed into applyLayers in place of the static request/browse config. The
  // generation bumps on config-set and on invalidateAllContent().
  private _layerGeneration = 0
  private _resolvedLayerGeneration = -1
  private _resolvedRequest?: TransformableRequestConfig
  private _resolvedBrowse?: TransformableRequestConfig
  // Dedupes concurrent resolution within a generation (config-set triggers an
  // initial navigate that can overlap with an explicit navigatePath), so a
  // resolver is invoked exactly once per generation even under concurrency.
  private _layerResolution?: { generation: number; promise: Promise<void> }

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
      const navError = error as {
        code: NavigationErrorType
        message: string
        statusCode?: number
      }
      this.navigationErrorManager.setNavigationError(
        navError.code,
        navError.message,
        path,
        navError.statusCode
      )
    } else {
      this.navigationErrorManager.setNavigationError(
        'network-error',
        'Failed to load content',
        path
      )
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
      const pathToNavigate =
        value ?? this._configuration.path ?? this.getDefaultPath()
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
    this._layerGeneration += 1
    this.navigationErrorManager.clearNavigationError()
    this.navigationErrorManager.setFormatCallback(value.formatNavigationError)

    // Determine initial path
    let initialPath = value.path

    // If no path specified, try to get first tab path
    if (!initialPath) {
      const tabsRoute = value.routes?.find((r) => r.path === '__tabs__')
      if (tabsRoute?.browseStatic?.children?.[0]?.path) {
        initialPath = tabsRoute.browseStatic.children[0].path
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
   * Navigates to a track's path.
   */
  async navigateTrack(track: Track): Promise<void> {
    this.navigationErrorManager.clearNavigationError()
    const path = track.path
    if (!path) {
      console.warn('Track has no path to navigate to')
      return
    }
    await this.navigate(path)
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
   * Invalidates all browse content. Web shows a single path at a time and
   * doesn't keep a content cache, so this just re-navigates the current path
   * to re-resolve it.
   */
  invalidateAllContent(): void {
    this._layerGeneration += 1
    if (this._path) {
      void this.navigate(this._path)
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

      // Add contextual paths to non-search content (matches Android behavior where
      // search() bypasses the resolve() contextual-path logic).
      // Shallow copy to avoid mutating the original config object (e.g., browseStatic
      // from routes), which would break search that reads from the same source.
      if (content?.children && !isSearchPath) {
        content = {
          ...content,
          children: this.addContextualPaths(path, content.children)
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
      const tabsRoute = this._configuration.routes?.find(
        (r) => r.path === '__tabs__'
      )
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

      this.handleNavigationError(error, path)
    }
  }

  /**
   * Stamps contextual paths onto a page's children. A playable track gets a
   * contextual path carrying its identity (id ?? src) so the queue can be
   * re-expanded from it later, plus its page position as a
   * duplicate-identity tie-breaker. Non-playable tracks are shallow-copied to
   * avoid mutating original config objects (e.g., browseStatic from routes).
   */
  private addContextualPaths(path: string, children: Track[]): Track[] {
    return children.map((track, index) => {
      const identity = getTrackIdentity(track)
      if (track.src && identity) {
        return {
          ...track,
          path: BrowserPathHelper.build(path, identity, index)
        }
      }
      return { ...track }
    })
  }

  /**
   * Resolves search content from a search path.
   * Returns a ResolvedTrack with raw children (no contextual URLs).
   * Matches Android's BrowserManager.search() which bypasses resolve() entirely.
   *
   * @param searchPath The search path (format: /__search?q=query)
   * @returns ResolvedTrack containing search results as children
   */
  private async resolveSearchContent(
    searchPath: string
  ): Promise<ResolvedTrack | undefined> {
    // Extract query from search path
    const queryMatch = searchPath.match(/[?&]q=([^&]*)/)
    if (!queryMatch) {
      console.warn('Invalid search path, missing query parameter:', searchPath)
      return undefined
    }
    const query = decodeURIComponent(queryMatch[1] ?? '')

    // Find __search__ route entry
    const searchRoute = this._configuration.routes?.find(
      (r) => r.path === '__search__'
    )
    if (!searchRoute) {
      console.warn('No __search__ route configured')
      return undefined
    }

    let searchResults: Track[] = []

    // Handle callback-based search
    if (searchRoute.searchCallback) {
      searchResults = await searchRoute.searchCallback({
        query,
        reference: 'unknown'
      })
    }
    // Handle request config-based search via the shared layered fetch.
    else if (searchRoute.searchConfig) {
      try {
        searchResults = await this.fetchSearchResults(
          searchRoute.searchConfig,
          {
            q: query
          }
        )
      } catch (error) {
        console.error('Search failed:', error)
        return undefined
      }
    }

    // Create ResolvedTrack with raw children
    // Search callbacks should return fresh tracks without contextual paths (like Android)
    return {
      path: searchPath,
      title: `Search: ${query}`,
      children: searchResults
    }
  }

  /**
   * Transforms artwork URLs for content and its children.
   * Supports both static config and resolve/transform callbacks.
   * Matches Android's artwork URL transformation with full Track access.
   */
  private async transformArtworkForContent(
    content: ResolvedTrack
  ): Promise<ResolvedTrack> {
    const artworkConfig = this._configuration.artwork
    if (!artworkConfig) {
      return content
    }
    // The shared request layer applies to artwork too — use the resolved layer
    // (resolver result or static config) so a resolver-only config still works.
    await this.ensureLayersResolved()
    const requestConfig = this._resolvedRequest

    // Transform parent artwork
    const parentArtworkSource =
      await RequestConfigBuilder.resolveArtworkSourceAsync(
        content,
        requestConfig,
        artworkConfig
      )

    // Transform children artwork
    let transformedChildren: Track[] | undefined
    if (content.children) {
      transformedChildren = await Promise.all(
        content.children.map(async (track) => {
          const artworkSource =
            await RequestConfigBuilder.resolveArtworkSourceAsync(
              track,
              requestConfig,
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
      children: transformedChildren ?? content.children
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
      browseCallback?: NativeBrowserConfiguration['routes'] extends
        | (infer R)[]
        | undefined
        ? R extends { browseCallback?: infer C }
          ? C
          : never
        : never
      browseStatic?: ResolvedTrack
      browseConfig?: NativeBrowserConfiguration['routes'] extends
        | (infer R)[]
        | undefined
        ? R extends { browseConfig?: infer C }
          ? C
          : never
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

    // Handle request config-based route via the layered request → browse → route
    // chain (matches native's resolveRouteEntry → resolveFromConfig).
    if (route.browseConfig) {
      try {
        return await this.resolveFromConfig(
          this._configuration.browse,
          route.browseConfig,
          path,
          routeParams
        )
      } catch (error: unknown) {
        console.error(`Failed to resolve ${errorContext}:`, error)
        this.handleNavigationError(error, path)
        return undefined
      }
    }

    return undefined
  }

  /**
   * Ensures the request/browse layer configs are resolved for the current
   * generation. Any present resolver is invoked once per generation and the
   * result cached; a static config (no resolver) passes through unchanged.
   */
  private async ensureLayersResolved(): Promise<void> {
    if (this._resolvedLayerGeneration === this._layerGeneration) return
    // Reuse an in-flight resolution for the same generation so concurrent
    // navigations (e.g. config-set's initial navigate overlapping an explicit
    // navigatePath) don't each invoke the resolver.
    if (this._layerResolution?.generation === this._layerGeneration) {
      return this._layerResolution.promise
    }
    const generation = this._layerGeneration
    const promise = (async () => {
      const cfg = this._configuration
      const resolvedRequest = cfg.requestResolver
        ? await cfg.requestResolver()
        : cfg.request
      const resolvedBrowse = cfg.browseResolver
        ? await cfg.browseResolver()
        : cfg.browse
      // Only commit if no newer generation superseded us mid-resolution.
      if (this._layerGeneration === generation) {
        this._resolvedRequest = resolvedRequest
        this._resolvedBrowse = resolvedBrowse
        this._resolvedLayerGeneration = generation
      }
    })().catch((err) => {
      // A failed resolution must not wedge the generation — clear the in-flight
      // cache so the next navigation retries (e.g. a transient async resolver error).
      if (this._layerResolution?.generation === generation) {
        this._layerResolution = undefined
      }
      throw err
    })
    this._layerResolution = { generation, promise }
    return promise
  }

  /**
   * Resolves content from the layered request config chain:
   * `request` (shared) → `kindConfig` (browse/search) → `routeConfig` (route).
   * Each layer's transform receives the previous layer's output; a layer with no
   * transform merges its static fields. `routeConfig` is undefined for the
   * implicit browse default; `kindConfig` is undefined for search.
   *
   * Mirrors native's BrowserManager.resolveFromConfig.
   */
  private async resolveFromConfig(
    kindConfig: TransformableRequestConfig | undefined,
    routeConfig: TransformableRequestConfig | undefined,
    path: string,
    params: Record<string, string>
  ): Promise<ResolvedTrack> {
    await this.ensureLayersResolved()
    const merged = await RequestConfigBuilder.applyLayers(
      { path },
      [
        this._resolvedRequest,
        // fall back to the route's kind config when no browse layer is configured
        this._resolvedBrowse ?? kindConfig,
        routeConfig
      ],
      params
    )
    const response = await this.httpClient.executeRequest(merged)
    return assertBrowsePageShape(response, path)
  }

  /**
   * Fetches search results from a `searchConfig` via the layered
   * `request → search` chain (search is its own kind — no browse layer). The
   * query params are seeded on the base so they survive both the static merge
   * and a search `transform`. Shared by `resolveSearchContent` (navigating to a
   * search path) and `SearchManager` (the voice/`search` API), so the ladder
   * isn't duplicated. Errors propagate to the caller.
   */
  async fetchSearchResults(
    searchConfig: TransformableRequestConfig,
    queryParams: Record<string, string>
  ): Promise<Track[]> {
    await this.ensureLayersResolved()
    const merged = await RequestConfigBuilder.applyLayers(
      { path: searchConfig.path, query: queryParams },
      [this._resolvedRequest, searchConfig],
      queryParams
    )
    const response = await this.httpClient.executeRequest(merged)
    return parseSearchResponse(response)
  }

  /**
   * Resolves content for a specific path using configured routes.
   */
  private async resolveContent(
    path: string
  ): Promise<ResolvedTrack | undefined> {
    const routes = this._configuration.routes

    if (routes && routes.length > 0) {
      // Convert routes array to record for SimpleRouter
      const routePatterns: Record<
        string,
        {
          browseCallback?: (typeof routes)[0]['browseCallback']
          browseConfig?: (typeof routes)[0]['browseConfig']
          browseStatic?: (typeof routes)[0]['browseStatic']
        }
      > = {}

      for (const route of routes) {
        // Skip special routes
        if (route.path.startsWith('__')) continue

        routePatterns[route.path] = {
          browseCallback: route.browseCallback,
          browseConfig: route.browseConfig,
          browseStatic: route.browseStatic
        }
      }

      // Try to match route
      const match = this.router.findBestMatch(path, routePatterns)
      if (match) {
        const [matchedPattern, routeMatch] = match
        const matchedRoute = routes.find((r) => r.path === matchedPattern)
        if (matchedRoute) {
          return this.resolveRouteContent(
            matchedRoute,
            path,
            routeMatch.params,
            'Route'
          )
        }
      }

      // Fall back to the custom __default__ route ('*') if configured
      const defaultRoute = routes.find((r) => r.path === '__default__')
      if (defaultRoute) {
        return this.resolveRouteContent(
          defaultRoute,
          path,
          { path },
          'Default route'
        )
      }
    }

    // Implicit default — no matching route, fetch the path via the layered
    // request → browse chain. Only attempt this when there's something to fetch
    // (a configured request or browse layer, static or resolver); otherwise
    // there's no content.
    if (
      this._configuration.request ||
      this._configuration.requestResolver ||
      this._configuration.browse ||
      this._configuration.browseResolver
    ) {
      try {
        return await this.resolveFromConfig(
          this._configuration.browse,
          undefined,
          path,
          { path }
        )
      } catch (error: unknown) {
        console.error('Failed to resolve default browse content:', error)
        this.handleNavigationError(error, path)
        return undefined
      }
    }

    return undefined
  }

  /**
   * Queries tabs from the __tabs__ route.
   */
  private async queryTabs(): Promise<Track[]> {
    const tabsRoute = this._configuration.routes?.find(
      (r) => r.path === '__tabs__'
    )
    if (!tabsRoute) {
      return []
    }

    const result = await this.resolveRouteContent(tabsRoute, '/', {}, 'Tabs')
    const tabs = result?.children ?? []

    // Transform artwork URLs on tabs via the async resolver so the shared
    // request layer (incl. its transform) applies, matching content/search.
    const artworkConfig = this._configuration.artwork
    if (!artworkConfig) return tabs
    // Resolved request layer (resolver or static), matching content/search.
    await this.ensureLayersResolved()
    const requestConfig = this._resolvedRequest
    return Promise.all(
      tabs.map(async (track) => {
        const artworkSource =
          await RequestConfigBuilder.resolveArtworkSourceAsync(
            track,
            requestConfig,
            artworkConfig
          )
        if (artworkSource && !track.artworkSource) {
          return { ...track, artworkSource }
        }
        return track
      })
    )
  }

  /**
   * Checks if the configuration has valid routes.
   */
  private hasValidConfiguration(): boolean {
    return (
      this._configuration.routes !== undefined &&
      this._configuration.routes.length > 0
    )
  }

  /**
   * Expands a queue from a contextual URL.
   * Resolves the parent container to get all siblings.
   * Matches Android's BrowserManager.expandQueueFromContextualPath() behavior.
   *
   * @param contextualPath The contextual path (format: /path?__trackId=trackSrc)
   * @returns Object with tracks and selectedIndex, or undefined if expansion fails
   */
  async expandQueueFromContextualPath(
    contextualPath: string
  ): Promise<{ tracks: Track[]; selectedIndex: number } | undefined> {
    const trackId = BrowserPathHelper.extractTrackId(contextualPath)
    if (!trackId) return undefined

    try {
      // Resolve the parent container to get all siblings
      const parentPath = BrowserPathHelper.stripTrackId(contextualPath)
      const parentResolvedTrack = await this.resolveContent(parentPath)
      const rawChildren = parentResolvedTrack?.children

      if (!rawChildren || rawChildren.length === 0) {
        console.warn('Parent has no children, cannot expand queue')
        return undefined
      }

      // Stamp contextual paths so queued tracks carry their own queue context
      // (navigate() stamps the displayed page the same way).
      const children = this.addContextualPaths(parentPath, rawChildren)

      // Filter to only playable tracks (tracks with src)
      const playableTracks = children.filter((track) => track.src != null)

      if (playableTracks.length === 0) {
        console.warn('Parent has no playable tracks, cannot expand queue')
        return undefined
      }

      // Find the index of the selected track by identity (id ?? src). The
      // stamped page index is a tie-breaker between duplicate identities: when
      // the child at that position still carries the tapped identity, it pins
      // the exact copy; a stale or absent index falls back to the first match.
      const tappedIndex = BrowserPathHelper.extractIndex(contextualPath)
      let selectedIndex = -1
      if (tappedIndex !== undefined) {
        const tapped = children[tappedIndex]
        if (tapped?.src != null && getTrackIdentity(tapped) === trackId) {
          selectedIndex =
            children
              .slice(0, tappedIndex + 1)
              .filter((track) => track.src != null).length - 1
        }
      }
      if (selectedIndex < 0) {
        selectedIndex = playableTracks.findIndex(
          (track) => getTrackIdentity(track) === trackId
        )
      }

      if (selectedIndex < 0) {
        console.warn(
          `Track with identity='${trackId}' not found in playable children`
        )
        return undefined
      }

      // Check singleTrack setting - if true, return only the selected track
      if (this._configuration.singleTrack) {
        return {
          tracks: [assertedNotNullish(playableTracks[selectedIndex])],
          selectedIndex: 0
        }
      }
      return { tracks: playableTracks, selectedIndex }
    } catch (error) {
      console.error(
        `Error expanding queue from contextual path: ${contextualPath}`,
        error
      )
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
    // Single track: check for search context or contextual path
    if (tracks.length === 1) {
      const track = assertedNotNullish(tracks[0])
      const trackPath = track.path

      // If search query present, expand search results
      if (searchQuery) {
        // Execute search (will hit cache if already performed)
        const searchResults = await this.resolveSearchContent(
          BrowserPathHelper.createSearchPath(searchQuery)
        )
        const searchTracks = searchResults?.children

        if (searchTracks && searchTracks.length > 0) {
          // Find the selected track in search results by path or identity
          // (matches Android's three-way mediaId match)
          const identity = getTrackIdentity(track)
          const selectedIdx = searchTracks.findIndex(
            (t) =>
              t.path === trackPath ||
              (identity !== undefined && getTrackIdentity(t) === identity)
          )

          if (selectedIdx >= 0) {
            return {
              tracks: searchTracks,
              startIndex: selectedIdx,
              startPositionMs
            }
          }
        }
      }

      // Check if contextual path - expand from parent
      // Note: The queueSourcePath optimization is handled by the caller (NativeAudioBrowser.navigateTrackAsync)
      // which has access to the existing queue and can skip to the track directly.
      // This matches Android where MediaSessionCallback handles the optimization before calling
      // BrowserManager.resolveMediaItemsForPlayback.
      if (trackPath && BrowserPathHelper.isContextual(trackPath)) {
        const parentPath = BrowserPathHelper.stripTrackId(trackPath)
        const expanded = await this.expandQueueFromContextualPath(trackPath)

        if (expanded) {
          // Store source path for optimization by caller on next invocation
          this._queueSourcePath = parentPath
          return {
            tracks: expanded.tracks,
            startIndex: expanded.selectedIndex,
            startPositionMs
          }
        }
      }
    }

    // No expansion - use tracks as-is
    return { tracks, startIndex, startPositionMs }
  }

  /**
   * Gets the default navigation path (first tab path or '/').
   */
  private getDefaultPath(): string {
    // Try to get first tab as default path
    if (this._tabs && this._tabs.length > 0) {
      const firstTab = this._tabs[0]
      return firstTab?.path ?? '/'
    }
    return '/'
  }
}
