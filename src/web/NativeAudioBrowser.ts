import type { AudioBrowser as AudioBrowserSpec } from '../specs/audio-browser.nitro'
import type {
  BrowserConfiguration,
  ResolvedTrack,
  Track,
  RequestConfig,
  TransformableRequestConfig,
} from '../types'
import { NativeAudioPlayer } from './NativeAudioPlayer'
import { SimpleRouter } from './SimpleRouter'

/**
 * Web implementation of AudioBrowser using Fetch API
 */
export class NativeAudioBrowser implements AudioBrowserSpec {
  // HybridObject stuff
  readonly name = 'WebAudioBrowser'
  equals(_other: unknown) {
    return true
  }
  dispose() {
    // cleanup if needed
  }

  // Internal state
  private _configuration: BrowserConfiguration = {}
  private _path: string | undefined
  private _tabs: Track[] | undefined
  private _content: ResolvedTrack | undefined
  private audioPlayer: NativeAudioPlayer | undefined
  private router = new SimpleRouter()

  // Public getters/setters
  get path(): string | undefined {
    return this._path
  }

  set path(value: string | undefined) {
    if (this.hasValidConfiguration()) {
      if (value || this._configuration.path) {
        this.launchNavigate(value ?? this._configuration.path ?? this.getDefaultPath())
      }
    }
  }

  get tabs(): Track[] | undefined {
    return this._tabs
  }

  set tabs(_value: Track[] | undefined) {
    // tabs are set internally via configuration
  }

  get configuration(): BrowserConfiguration {
    return this._configuration
  }

  set configuration(value: BrowserConfiguration) {
    this._configuration = value
    // Navigate to initial path or default to first tab
    const initialPath = value.path ?? this.getDefaultPath()
    if (initialPath) {
      this.launchNavigate(initialPath)
    }
  }

  // Event callbacks (initialized to no-ops, can be set by consumers)
  onPathChanged: (path: string) => void = () => {}
  onContentChanged: (content: ResolvedTrack | undefined) => void = () => {}
  onTabsChanged: (tabs: Track[]) => void = () => {}

  /** Internal method called by AudioPlayer.registerBrowser() to establish the connection. */
  setAudioPlayer(audioPlayer: NativeAudioPlayer): void {
    this.audioPlayer = audioPlayer
  }

  /**
   * Helper to launch async navigation without blocking (fire-and-forget).
   * Similar to Kotlin's mainScope.launch { browserManager.navigate(it) }
   * Errors are handled within navigate() itself.
   */
  private launchNavigate(path: string): void {
    // Explicitly ignore the promise - navigation happens in background
    void this.navigate(path)
  }

  // Browser navigation methods
  navigatePath(path: string): void {
    this.launchNavigate(path)
  }

  navigateTrack(track: Track): void {
    const url = track.url
    if (url) {
      // Navigate to browsable track
      this.launchNavigate(url)
    } else if (track.src || track.playable === true) {
      // Load playable track into player
      if (!this.audioPlayer) {
        throw new Error(
          'AudioPlayer not registered. Call audioPlayer.registerBrowser(audioBrowser) first.'
        )
      }

      // Just load the single track (matches Android behavior)
      this.audioPlayer.load(track)
    } else {
      throw new Error(
        "Track must have either 'url', 'src', or 'playable' property"
      )
    }
  }

  async onSearch(query: string): Promise<Track[]> {
    const searchConfig = this._configuration.search
    if (!searchConfig) {
      return []
    }

    // Handle callback-based search
    if (typeof searchConfig === 'function') {
      return searchConfig(query)
    }

    // Handle request config-based search
    const requestConfig = this.mergeRequestConfig(searchConfig, {
      query: { q: query }
    })

    try {
      const response = await this.executeRequest(requestConfig)
      return Array.isArray(response) ? response : []
    } catch (error) {
      console.error('Search failed:', error)
      return []
    }
  }

  getContent(): ResolvedTrack | undefined {
    return this._content
  }

  // Internal navigation logic
  private async navigate(path: string): Promise<void> {
    try {
      // Update path
      this._path = path
      this.onPathChanged(path)

      // Resolve content for this path
      const content = await this.resolveContent(path)
      this._content = content
      this.onContentChanged(content)

      // Query and update tabs if configuration has tabs source
      if (this._configuration.tabs) {
        const tabs = await this.queryTabs()
        this._tabs = tabs
        this.onTabsChanged(tabs)
      }
    } catch (error) {
      console.error('Navigation failed:', error)
      this._content = undefined
      this.onContentChanged(undefined)
    }
  }

  private async resolveContent(path: string): Promise<ResolvedTrack | undefined> {
    // First, try to match against configured routes using pattern matching
    const routes = this._configuration.routes
    if (routes && Object.keys(routes).length > 0) {
      const match = this.router.findBestMatch(path, routes)
      if (match) {
        const [routePattern, routeMatch] = match
        const routeSource = routes[routePattern]
        const routeParams = routeMatch.params

        // Handle callback-based route
        if (typeof routeSource === 'function') {
          return routeSource({ path, routeParams })
        }

        // Handle static ResolvedTrack route
        if ('url' in routeSource && typeof routeSource.url === 'string') {
          return routeSource as ResolvedTrack
        }

        // Handle request config-based route
        const requestConfig = this.mergeRequestConfig(
          routeSource as TransformableRequestConfig,
          { path: path }
        )

        try {
          const response = await this.executeRequest(requestConfig)
          return response as ResolvedTrack
        } catch (error) {
          console.error('Failed to resolve route:', error)
          return undefined
        }
      }
    }

    // Fall back to browse configuration
    const browseConfig = this._configuration.browse
    if (!browseConfig) {
      return undefined
    }

    // Handle callback-based browse
    if (typeof browseConfig === 'function') {
      return browseConfig({ path, routeParams: {} })
    }

    // Handle static ResolvedTrack
    if ('url' in browseConfig && typeof browseConfig.url === 'string') {
      return browseConfig
    }

    // Handle request config-based browse (TransformableRequestConfig)
    // At this point, we've eliminated function and ResolvedTrack, so it must be TransformableRequestConfig
    const requestConfig = this.mergeRequestConfig(
      browseConfig as TransformableRequestConfig,
      { path: path }
    )

    try {
      const response = await this.executeRequest(requestConfig)
      return response as ResolvedTrack
    } catch (error) {
      console.error('Failed to resolve content:', error)
      return undefined
    }
  }

  private async queryTabs(): Promise<Track[]> {
    const tabsConfig = this._configuration.tabs
    if (!tabsConfig) {
      return []
    }

    // Handle static array
    if (Array.isArray(tabsConfig)) {
      return tabsConfig
    }

    // Handle callback
    if (typeof tabsConfig === 'function') {
      return tabsConfig()
    }

    // Handle request config
    try {
      const requestConfig = this.mergeRequestConfig(tabsConfig, {
        path: '/'
      })
      const response = await this.executeRequest(requestConfig)
      return Array.isArray(response) ? response : []
    } catch (error) {
      console.error('Failed to query tabs:', error)
      return []
    }
  }

  private hasValidConfiguration(): boolean {
    return (
      this._configuration.tabs !== undefined ||
      this._configuration.routes !== undefined ||
      this._configuration.browse !== undefined
    )
  }

  private getDefaultPath(): string {
    // Try to get first tab as default path
    if (this._tabs && this._tabs.length > 0) {
      const firstTab = this._tabs[0]
      return firstTab?.url ?? '/'
    }
    return '/'
  }

  private mergeRequestConfig(
    base: Partial<TransformableRequestConfig>,
    overrides: Partial<RequestConfig>
  ): RequestConfig {
    const baseRequest = this._configuration.request ?? {}
    return {
      ...baseRequest,
      ...base,
      ...overrides,
      headers: {
        ...baseRequest.headers,
        ...base?.headers,
        ...overrides?.headers,
      },
      query: {
        ...baseRequest.query,
        ...base?.query,
        ...overrides?.query,
      },
    }
  }

  private async executeRequest(config: RequestConfig): Promise<unknown> {
    const url = this.buildUrl(config)
    const headers = config.headers ?? {}
    const method = config.method ?? 'GET'

    const response = await fetch(url, {
      method,
      headers,
    })

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`)
    }

    return response.json()
  }

  private buildUrl(config: RequestConfig): string {
    let url = config.baseUrl ?? ''
    const path = config.path ?? ''

    // Append path
    if (path) {
      url = url.endsWith('/') ? url.slice(0, -1) : url
      url += path.startsWith('/') ? path : `/${path}`
    }

    // Append query parameters
    const query = config.query
    if (query && Object.keys(query).length > 0) {
      const params = new URLSearchParams()
      for (const [key, value] of Object.entries(query)) {
        if (value !== undefined && value !== null) {
          params.append(key, String(value))
        }
      }
      const queryString = params.toString()
      if (queryString) {
        url += `?${queryString}`
      }
    }

    return url
  }
}
