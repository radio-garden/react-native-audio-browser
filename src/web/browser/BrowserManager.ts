import type { Track, ResolvedTrack } from '../../types'
import type { NativeBrowserConfiguration } from '../../types/browser-native'
import type { NavigationErrorType } from '../../features'
import { SimpleRouter } from '../SimpleRouter'
import type { HttpClient } from '../http/HttpClient'
import type { FavoriteManager } from './FavoriteManager'
import type { NavigationErrorManager } from './NavigationErrorManager'

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
   */
  private async navigate(path: string): Promise<void> {
    try {
      // Update path
      this._path = path
      this.onPathChanged(path)

      // Resolve content for this path
      let content = await this.resolveContent(path)

      // Transform playable-only tracks to add contextual URLs (matches Android behavior)
      // This enables queue expansion when clicking tracks
      if (content?.children) {
        content.children = content.children.map(track => {
          // If track has src but no url (playable-only), add contextual URL
          if (track.src && !track.url) {
            const contextualUrl = `${path}?__trackId=${encodeURIComponent(track.src)}`
            return { ...track, url: contextualUrl }
          }
          return track
        })
      }

      // Hydrate favorites on all children
      if (content) {
        content = this.favoriteManager.hydrateChildren(content)
      }

      this._content = content
      this.onContentChanged(content)

      // Query and update tabs if configuration has __tabs__ route
      const tabsRoute = this._configuration.routes?.find(r => r.path === '__tabs__')
      if (tabsRoute) {
        const tabs = await this.queryTabs()
        this._tabs = tabs
        this.onTabsChanged(tabs)
      }
    } catch (error) {
      console.error('Navigation failed:', error)
      this._content = undefined
      this.onContentChanged(undefined)

      // Set navigation error using helper
      const message = error instanceof Error ? error.message : 'Unknown error'
      this.navigationErrorManager.setNavigationError('unknown-error', message)
    }
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
        const routeParams = routeMatch.params

        // Handle callback-based route
        if (matchedRoute.browseCallback) {
          const result = await matchedRoute.browseCallback({ path, routeParams })
          // Check for BrowseError
          if ('error' in result) {
            console.error('Browse error:', result.error)
            return undefined
          }
          return result
        }

        // Handle static ResolvedTrack route
        if (matchedRoute.browseStatic) {
          return matchedRoute.browseStatic
        }

        // Handle request config-based route
        if (matchedRoute.browseConfig) {
          const requestConfig = this.httpClient.mergeRequestConfig(matchedRoute.browseConfig, { path })

          try {
            const response = await this.httpClient.executeRequest(requestConfig)
            return response as ResolvedTrack
          } catch (error: unknown) {
            console.error('Failed to resolve route:', error)
            // Set navigation error with proper code and message
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
            return undefined
          }
        }
      }
    }

    // Fall back to __default__ route
    const defaultRoute = routes.find(r => r.path === '__default__')
    if (defaultRoute) {
      if (defaultRoute.browseCallback) {
        const result = await defaultRoute.browseCallback({ path, routeParams: {} })
        // Check for BrowseError
        if ('error' in result) {
          console.error('Browse error:', result.error)
          return undefined
        }
        return result
      }
      if (defaultRoute.browseStatic) {
        return defaultRoute.browseStatic
      }
      if (defaultRoute.browseConfig) {
        const requestConfig = this.httpClient.mergeRequestConfig(defaultRoute.browseConfig, { path })
        try {
          const response = await this.httpClient.executeRequest(requestConfig)
          return response as ResolvedTrack
        } catch (error: unknown) {
          console.error('Failed to resolve default route:', error)
          // Set navigation error with proper code and message
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
          return undefined
        }
      }
    }

    return undefined
  }

  /**
   * Queries tabs from the __tabs__ route.
   */
  private async queryTabs(): Promise<Track[]> {
    // Find __tabs__ route entry
    const tabsRoute = this._configuration.routes?.find(r => r.path === '__tabs__')
    if (!tabsRoute) {
      return []
    }

    // Handle callback-based tabs
    if (tabsRoute.browseCallback) {
      const result = await tabsRoute.browseCallback({ path: '/', routeParams: {} })
      // Check for BrowseError
      if ('error' in result) {
        console.error('Tabs browse error:', result.error)
        return []
      }
      return result.children ?? []
    }

    // Handle static tabs
    if (tabsRoute.browseStatic) {
      return tabsRoute.browseStatic.children ?? []
    }

    // Handle request config-based tabs
    if (tabsRoute.browseConfig) {
      try {
        const requestConfig = this.httpClient.mergeRequestConfig(tabsRoute.browseConfig, { path: '/' })
        const response = await this.httpClient.executeRequest(requestConfig)
        if (Array.isArray(response)) {
          return response as Track[]
        }
        const resolvedTrack = response as ResolvedTrack
        return resolvedTrack.children ?? []
      } catch (error) {
        console.error('Failed to query tabs:', error)
        return []
      }
    }

    return []
  }

  /**
   * Checks if the configuration has valid routes.
   */
  private hasValidConfiguration(): boolean {
    return this._configuration.routes !== undefined && this._configuration.routes.length > 0
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
