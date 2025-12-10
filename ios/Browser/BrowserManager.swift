import Foundation
import NitroModules
import os.log

/// Custom browser errors.
enum BrowserError: Error {
  case contentNotFound(path: String)
  case httpError(code: Int, body: String)
  case networkError(Error)
  case invalidConfiguration(String)

  var localizedDescription: String {
    switch self {
    case let .contentNotFound(path):
      "No content found for path: \(path)"
    case let .httpError(code, body):
      "HTTP error \(code): \(body)"
    case let .networkError(error):
      "Network error: \(error.localizedDescription)"
    case let .invalidConfiguration(message):
      "Invalid configuration: \(message)"
    }
  }
}

/// Core browser manager that handles navigation, search, and media browsing.
///
/// This class contains the main business logic for:
/// - Route resolution and path matching with parameter extraction
/// - HTTP API requests and response processing
/// - JavaScript callback invocation
/// - Fallback handling and error management
final class BrowserManager {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "BrowserManager")

  // MARK: - Constants (match Kotlin companion object)

  /// Internal path used for the default/root browse source
  static let defaultRoutePath = "__default__"

  /// Internal path used for navigation tabs
  static let tabsRoutePath = "__tabs__"

  /// Internal path used for search
  static let searchRoutePath = "__search__"

  // MARK: - Private Properties

  private let router = SimpleRouter()
  private let httpClient = HttpClient()

  // LRU cache for individual tracks - keyed by both url and src for O(1) lookup
  private let trackCache = LRUCache<String, Track>(maxSize: 3000)

  // LRU cache for resolved content - keyed by path
  private let contentCache = LRUCache<String, ResolvedTrack>(maxSize: 20)

  // Cache for search results
  private var lastSearchQuery: String?
  private var lastSearchResults: [Track]?

  // Set of favorited track identifiers (src)
  private var favoriteIds: Set<String> = []

  // Navigation tracking to prevent race conditions
  private var currentNavigationId: Int = 0

  // MARK: - Public State

  /// Current navigation path. Must be modified on main thread.
  private(set) var path: String = "/" {
    didSet {
      assertMainThread()
      if oldValue != path {
        onPathChanged?(path)
      }
    }
  }

  /// Current resolved content. Must be modified on main thread.
  private(set) var content: ResolvedTrack? {
    didSet {
      assertMainThread()
      // Note: Can't compare ResolvedTrack directly, always fire callback
      onContentChanged?(content)
    }
  }

  /// Current tabs. Must be modified on main thread.
  private(set) var tabs: [Track]? {
    didSet {
      assertMainThread()
      if let tabs {
        onTabsChanged?(tabs)
      }
    }
  }

  private func assertMainThread() {
    assert(Thread.isMainThread, "BrowserManager state must be modified on main thread")
  }

  // MARK: - Configuration

  /// Browser configuration containing routes, search, tabs, and request settings.
  var config: BrowserConfig = .init() {
    didSet {
      onConfigChanged?(config)
    }
  }

  /// Callback to transform artwork URLs for tracks.
  var artworkUrlResolver: ((Track, MediaRequestConfig?) async -> ImageSource?)?

  // MARK: - Callbacks

  var onPathChanged: ((String) -> Void)?
  var onContentChanged: ((ResolvedTrack?) -> Void)?
  var onTabsChanged: (([Track]) -> Void)?
  var onConfigChanged: ((BrowserConfig) -> Void)?

  // MARK: - Favorites

  /// Sets the favorited track identifiers.
  func setFavorites(_ favorites: [String]) {
    favoriteIds = Set(favorites)
  }

  /// Updates the favorite state for a single track identifier.
  func updateFavorite(id: String, favorited: Bool) {
    if favorited {
      favoriteIds.insert(id)
    } else {
      favoriteIds.remove(id)
    }
  }

  /// Hydrates the favorited field on a track based on the favoriteIds set.
  /// Local favoriteIds always take precedence over API-provided values.
  private func hydrateFavorite(_ track: Track) -> Track {
    guard let src = track.src else { return track }

    // Check if this track is in our local favorites set
    let isFavorited = favoriteIds.contains(src)

    // Only create a new track if the favorited state differs
    if track.favorited == isFavorited { return track }

    return Track(
      url: track.url,
      src: track.src,
      artwork: track.artwork,
      artworkSource: track.artworkSource,
      title: track.title,
      subtitle: track.subtitle,
      artist: track.artist,
      album: track.album,
      description: track.description,
      genre: track.genre,
      duration: track.duration,
      style: track.style,
      childrenStyle: track.childrenStyle,
      favorited: isFavorited,
      groupTitle: track.groupTitle
    )
  }

  /// Hydrates favorites on all children of a ResolvedTrack.
  private func hydrateChildren(_ resolvedTrack: ResolvedTrack) -> ResolvedTrack {
    guard let children = resolvedTrack.children else { return resolvedTrack }
    let hydratedChildren = children.map { hydrateFavorite($0) }
    return ResolvedTrack(
      url: resolvedTrack.url,
      children: hydratedChildren,
      src: resolvedTrack.src,
      artwork: resolvedTrack.artwork,
      artworkSource: resolvedTrack.artworkSource,
      title: resolvedTrack.title,
      subtitle: resolvedTrack.subtitle,
      artist: resolvedTrack.artist,
      album: resolvedTrack.album,
      description: resolvedTrack.description,
      genre: resolvedTrack.genre,
      duration: resolvedTrack.duration,
      style: resolvedTrack.style,
      childrenStyle: resolvedTrack.childrenStyle,
      favorited: resolvedTrack.favorited,
      groupTitle: resolvedTrack.groupTitle
    )
  }

  // MARK: - Track Cache

  /// Cache a track by both url and src for O(1) lookup from either key.
  private func cacheTrack(_ track: Track) {
    if let url = track.url {
      trackCache.set(url, value: track)
    }
    if let src = track.src {
      trackCache.set(src, value: track)
    }
  }

  private func cacheChildren(_ resolvedTrack: ResolvedTrack) {
    resolvedTrack.children?.forEach { cacheTrack($0) }
  }

  /// Get a cached Track by mediaId (url or src), or nil if not cached.
  func getCachedTrack(_ mediaId: String) -> Track? {
    // Try direct lookup first (matches url or src)
    if let track = trackCache.get(mediaId) {
      return hydrateFavorite(track)
    }

    // Try extracting src from contextual URL
    if let trackId = BrowserPathHelper.extractTrackId(mediaId) {
      if let track = trackCache.get(trackId) {
        return hydrateFavorite(track)
      }
    }

    return nil
  }

  // MARK: - Navigation

  /// Main navigation method - updates path and resolves content.
  ///
  /// Uses a navigation ID to prevent race conditions when multiple navigations
  /// overlap. Only the most recent navigation's result is applied.
  @MainActor
  func navigate(_ path: String) async throws {
    // Increment navigation ID and capture for this navigation
    currentNavigationId += 1
    let navigationId = currentNavigationId

    self.path = path
    content = nil // Clear for loading state

    let resolved = try await resolve(path, useCache: false)

    // Only apply result if this is still the current navigation
    guard navigationId == currentNavigationId else { return }

    content = resolved
  }

  /// Resolves content for a path with optional caching.
  func resolve(_ path: String, useCache: Bool = true) async throws -> ResolvedTrack {
    // Strip __trackId from contextual URLs
    let normalizedPath = BrowserPathHelper.stripTrackId(path)

    // Check content cache first
    if useCache, let cached = contentCache.get(normalizedPath) {
      return hydrateChildren(cached)
    }

    let resolvedTrack = try await resolveUncached(normalizedPath)

    // Cache the resolved content for future navigation
    contentCache.set(normalizedPath, value: resolvedTrack)

    // Cache children for track lookups
    cacheChildren(resolvedTrack)

    return hydrateChildren(resolvedTrack)
  }

  /// Invalidates the content cache for a specific path.
  func invalidateContentCache(_ path: String) {
    contentCache.remove(path)
  }

  /// Refreshes the current path by invalidating cache and re-resolving.
  ///
  /// Uses navigation ID tracking to prevent race conditions.
  @MainActor
  func refresh() async throws {
    // Increment navigation ID and capture for this refresh
    currentNavigationId += 1
    let navigationId = currentNavigationId

    let currentPath = path
    contentCache.remove(currentPath)
    let resolved = try await resolve(currentPath, useCache: false)

    // Only apply result if this is still the current navigation
    guard navigationId == currentNavigationId else { return }

    content = resolved
  }

  private func resolveUncached(_ path: String) async throws -> ResolvedTrack {
    guard let routes = config.routes, !routes.isEmpty else {
      throw BrowserError.contentNotFound(path: path)
    }

    // Find best matching route
    guard let (routeEntry, routeMatch) = findBestRouteMatch(path: path, routes: routes) else {
      throw BrowserError.contentNotFound(path: path)
    }

    // Resolve the track from the route
    let resolvedTrack = try await resolveRouteEntry(routeEntry, path: path, params: routeMatch.params)

    // Validate and transform children
    var transformedChildren: [Track]?
    if let children = resolvedTrack.children {
      transformedChildren = try await transformChildren(children, parentPath: path, routeEntry: routeEntry)
    }

    if transformedChildren != nil {
      return ResolvedTrack(
        url: resolvedTrack.url,
        children: transformedChildren,
        src: resolvedTrack.src,
        artwork: resolvedTrack.artwork,
        artworkSource: resolvedTrack.artworkSource,
        title: resolvedTrack.title,
        subtitle: resolvedTrack.subtitle,
        artist: resolvedTrack.artist,
        album: resolvedTrack.album,
        description: resolvedTrack.description,
        genre: resolvedTrack.genre,
        duration: resolvedTrack.duration,
        style: resolvedTrack.style,
        childrenStyle: resolvedTrack.childrenStyle,
        favorited: resolvedTrack.favorited,
        groupTitle: resolvedTrack.groupTitle
      )
    }

    return resolvedTrack
  }

  // MARK: - Route Resolution

  private func findBestRouteMatch(
    path: String,
    routes: [NativeRouteEntry]
  ) -> (NativeRouteEntry, RouteMatch)? {
    // Filter out special routes
    let browseRoutes = routes.filter {
      $0.path != Self.tabsRoutePath &&
        $0.path != Self.searchRoutePath &&
        $0.path != Self.defaultRoutePath
    }

    // Try to find a matching route
    if let match = router.findBestMatch(path: path, routes: browseRoutes) {
      return match
    }

    // Fall back to __default__ if present
    if let defaultRoute = routes.first(where: { $0.path == Self.defaultRoutePath }) {
      return (defaultRoute, RouteMatch(params: ["path": path]))
    }

    return nil
  }

  private func resolveRouteEntry(
    _ entry: NativeRouteEntry,
    path: String,
    params: [String: String]
  ) async throws -> ResolvedTrack {
    // Priority: callback > config > static
    if let callback = entry.browseCallback {
      let callbackParam = BrowserSourceCallbackParam(path: path, routeParams: params)
      // Callback returns Promise<Promise<ResolvedTrack>> - await both layers
      let outerPromise = callback(callbackParam)
      let innerPromise = try await outerPromise.await()
      return try await innerPromise.await()
    }

    if let browseConfig = entry.browseConfig {
      return try await resolveFromConfig(browseConfig, path: path, params: params)
    }

    if let staticContent = entry.browseStatic {
      return staticContent
    }

    throw BrowserError.contentNotFound(path: path)
  }

  private func resolveFromConfig(
    _ routeConfig: TransformableRequestConfig,
    path: String,
    params _: [String: String]
  ) async throws -> ResolvedTrack {
    // Build the request URL - route config takes precedence over global config
    let baseUrl = routeConfig.baseUrl ?? config.request?.baseUrl
    guard let baseUrl else {
      throw BrowserError.invalidConfiguration("No URL configured for route")
    }

    var url = BrowserPathHelper.buildUrl(baseUrl: baseUrl, path: path)

    // Add query parameters from config - route config values override global config
    let queryParams = mergeQuery(config.request?.query, routeConfig.query)
    if let queryParams, !queryParams.isEmpty {
      let queryString = queryParams
        .map { key, value in
          let encodedKey = key.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? key
          let encodedValue = value.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? value
          return "\(encodedKey)=\(encodedValue)"
        }
        .joined(separator: "&")
      let separator = url.contains("?") ? "&" : "?"
      url = "\(url)\(separator)\(queryString)"
    }

    // Build HTTP request - route config takes precedence
    let method = (routeConfig.method ?? config.request?.method)?.stringValue ?? "GET"
    let request = HttpClient.HttpRequest(
      url: url,
      method: method,
      headers: mergeHeaders(config.request?.headers, routeConfig.headers)
    )

    logger.debug("Resolving content from API")
    logger.debug("  path: \(path)")
    logger.debug("  url: \(url)")

    // Execute request
    let result: JsonResolvedTrack = try await httpClient.requestJson(request, as: JsonResolvedTrack.self)
    let nitroResult = result.toNitro()

    logger.debug("Resolved: \(nitroResult.title)")
    if let children = nitroResult.children {
      logger.debug("  children: \(children.count) tracks")
    }

    return nitroResult
  }

  private func mergeQuery(
    _ base: [String: String]?,
    _ override: [String: String]?
  ) -> [String: String]? {
    guard let base else { return override }
    guard let override else { return base }
    return base.merging(override) { _, new in new }
  }

  private func mergeHeaders(
    _ base: [String: String]?,
    _ override: [String: String]?
  ) -> [String: String]? {
    guard let base else { return override }
    guard let override else { return base }
    return base.merging(override) { _, new in new }
  }

  // MARK: - Child Transformation

  private func transformChildren(
    _ children: [Track],
    parentPath: String,
    routeEntry: NativeRouteEntry
  ) async throws -> [Track] {
    var transformed: [Track] = []

    for track in children {
      // Validate track has stable identifier
      if track.url == nil, track.src == nil {
        throw BrowserError.invalidConfiguration(
          "Track must have either 'url' or 'src' for stable identification: \(track.title)"
        )
      }

      var transformedTrack = track

      // Generate contextual URL for playable-only tracks (has src but no url)
      if track.src != nil, track.url == nil {
        let contextualUrl = BrowserPathHelper.build(parentPath: parentPath, trackId: track.src!)
        transformedTrack = Track(
          url: contextualUrl,
          src: track.src,
          artwork: track.artwork,
          artworkSource: track.artworkSource,
          title: track.title,
          subtitle: track.subtitle,
          artist: track.artist,
          album: track.album,
          description: track.description,
          genre: track.genre,
          duration: track.duration,
          style: track.style,
          childrenStyle: track.childrenStyle,
          favorited: track.favorited,
          groupTitle: track.groupTitle
        )
      }

      // Transform artwork URL if resolver is configured
      if let resolver = artworkUrlResolver {
        let artworkConfig = routeEntry.artwork ?? config.artwork
        if let imageSource = await resolver(transformedTrack, artworkConfig) {
          transformedTrack = Track(
            url: transformedTrack.url,
            src: transformedTrack.src,
            artwork: transformedTrack.artwork,
            artworkSource: imageSource,
            title: transformedTrack.title,
            subtitle: transformedTrack.subtitle,
            artist: transformedTrack.artist,
            album: transformedTrack.album,
            description: transformedTrack.description,
            genre: transformedTrack.genre,
            duration: transformedTrack.duration,
            style: transformedTrack.style,
            childrenStyle: transformedTrack.childrenStyle,
            favorited: transformedTrack.favorited,
            groupTitle: transformedTrack.groupTitle
          )
        }
      }

      transformed.append(transformedTrack)
    }

    return transformed
  }

  // MARK: - Search

  /// Execute a search query.
  func search(_ query: String) async throws -> ResolvedTrack {
    // Check cache - re-hydrate favorites since they may have changed
    if query == lastSearchQuery, let results = lastSearchResults {
      let hydratedResults = results.map { hydrateFavorite($0) }
      return ResolvedTrack(
        url: BrowserPathHelper.createSearchPath(query),
        children: hydratedResults,
        src: nil,
        artwork: nil,
        artworkSource: nil,
        title: "Search: \(query)",
        subtitle: nil,
        artist: nil,
        album: nil,
        description: nil,
        genre: nil,
        duration: nil,
        style: nil,
        childrenStyle: nil,
        favorited: nil,
        groupTitle: nil
      )
    }

    guard let routes = config.routes else {
      throw BrowserError.contentNotFound(path: Self.searchRoutePath)
    }

    guard let searchEntry = routes.first(where: { $0.path == Self.searchRoutePath }) else {
      throw BrowserError.contentNotFound(path: Self.searchRoutePath)
    }

    let searchParams = SearchParams(
      mode: nil,
      query: query,
      genre: nil,
      artist: nil,
      album: nil,
      title: nil,
      playlist: nil
    )

    var results: [Track]

    if let callback = searchEntry.searchCallback {
      // Callback returns Promise<Promise<[Track]>> - await both layers
      let outerPromise = callback(searchParams)
      let innerPromise = try await outerPromise.await()
      results = try await innerPromise.await()
    } else if let searchConfig = searchEntry.searchConfig {
      let resolved = try await resolveFromConfig(searchConfig, path: "/__search", params: ["q": query])
      results = resolved.children ?? []
    } else {
      throw BrowserError.contentNotFound(path: Self.searchRoutePath)
    }

    // Hydrate favorites in results
    let hydratedResults = results.map { hydrateFavorite($0) }

    // Cache results
    lastSearchQuery = query
    lastSearchResults = hydratedResults

    // Cache individual tracks
    hydratedResults.forEach { cacheTrack($0) }

    return ResolvedTrack(
      url: BrowserPathHelper.createSearchPath(query),
      children: hydratedResults,
      src: nil,
      artwork: nil,
      artworkSource: nil,
      title: "Search: \(query)",
      subtitle: nil,
      artist: nil,
      album: nil,
      description: nil,
      genre: nil,
      duration: nil,
      style: nil,
      childrenStyle: nil,
      favorited: nil,
      groupTitle: nil
    )
  }

  // MARK: - Tabs

  /// Query navigation tabs.
  @MainActor
  func queryTabs() async throws -> [Track] {
    guard let routes = config.routes else {
      return []
    }

    guard let tabsEntry = routes.first(where: { $0.path == Self.tabsRoutePath }) else {
      return []
    }

    let resolved = try await resolveRouteEntry(tabsEntry, path: Self.tabsRoutePath, params: [:])
    let tabTracks = resolved.children ?? []

    tabs = tabTracks
    return tabTracks
  }

  // MARK: - Queue Expansion (for CarPlay/external controllers)

  /// Expands a contextual URL to a full queue of playable tracks.
  func expandQueueFromContextualUrl(_ url: String) async throws -> (tracks: [Track], selectedIndex: Int)? {
    guard BrowserPathHelper.isContextual(url) else { return nil }

    let parentPath = BrowserPathHelper.stripTrackId(url)
    guard let trackId = BrowserPathHelper.extractTrackId(url) else { return nil }

    logger.debug("Expanding queue from contextual URL")
    logger.debug("  url: \(url)")
    logger.debug("  parentPath: \(parentPath)")
    logger.debug("  trackId: \(trackId)")

    // Resolve the parent container
    let resolved = try await resolve(parentPath, useCache: true)
    guard let children = resolved.children else { return nil }

    // Filter to playable tracks (have src)
    let playableTracks = children.filter { $0.src != nil }
    guard !playableTracks.isEmpty else { return nil }

    logger.debug("Found \(playableTracks.count) playable tracks")
    for (index, track) in playableTracks.enumerated() {
      logger.debug("  [\(index)] \(track.title) - src: \(track.src ?? "nil")")
    }

    // Find selected track index
    let selectedIndex = playableTracks.firstIndex { $0.src == trackId } ?? 0
    logger.debug("Selected track index: \(selectedIndex)")

    // If singleTrack mode, return just the selected track
    if config.singleTrack {
      return (tracks: [playableTracks[selectedIndex]], selectedIndex: 0)
    }

    return (tracks: playableTracks, selectedIndex: selectedIndex)
  }

  // MARK: - Media URL Resolution

  /// Resolves a media URL using the configured media transform.
  /// Returns the transformed URL, headers, and user-agent for playback.
  func resolveMediaUrl(_ originalUrl: String) async -> MediaResolvedUrl {
    guard let mediaConfig = config.media else {
      logger.debug("No media config, using original URL: \(originalUrl)")
      return MediaResolvedUrl(url: originalUrl, headers: nil, userAgent: nil)
    }

    logger.debug("Resolving media URL: \(originalUrl)")

    // If there's a transform function, call it
    if let transform = mediaConfig.transform {
      do {
        // Create base request config with original URL as path
        let baseRequest = RequestConfig(
          method: config.request?.method,
          path: originalUrl,
          baseUrl: config.request?.baseUrl,
          headers: config.request?.headers,
          query: config.request?.query,
          body: config.request?.body,
          contentType: config.request?.contentType,
          userAgent: config.request?.userAgent
        )

        // Call the JS transform function on main thread (required for Nitro JS callbacks)
        let outerPromise = await MainActor.run { transform(baseRequest, nil) }
        let innerPromise = try await outerPromise.await()
        let transformedConfig = try await innerPromise.await()

        // Extract values immediately to Swift native types to avoid
        // memory corruption in Nitro's Swift-C++ bridge when the
        // Promise<RequestConfig> is deallocated
        let finalUrl = buildUrl(from: transformedConfig)
        let headers = transformedConfig.headers
        let userAgent = transformedConfig.userAgent

        logger.debug("Media URL transformed: \(originalUrl) -> \(finalUrl)")

        return MediaResolvedUrl(
          url: finalUrl,
          headers: headers,
          userAgent: userAgent
        )
      } catch {
        logger.error("Media transform failed: \(error.localizedDescription)")
        return MediaResolvedUrl(url: originalUrl, headers: nil, userAgent: nil)
      }
    }

    // No transform, just apply baseUrl if configured
    let baseUrl = mediaConfig.baseUrl ?? config.request?.baseUrl
    if let baseUrl {
      let finalUrl = BrowserPathHelper.buildUrl(baseUrl: baseUrl, path: originalUrl)
      logger.debug("Media URL with baseUrl: \(originalUrl) -> \(finalUrl)")
      return MediaResolvedUrl(
        url: finalUrl,
        headers: mediaConfig.headers ?? config.request?.headers,
        userAgent: mediaConfig.userAgent ?? config.request?.userAgent
      )
    }

    return MediaResolvedUrl(url: originalUrl, headers: nil, userAgent: nil)
  }

  private func buildUrl(from config: RequestConfig) -> String {
    let path = config.path ?? ""
    let baseUrl = config.baseUrl

    var url = BrowserPathHelper.buildUrl(baseUrl: baseUrl, path: path)

    // Add query parameters if any
    if let query = config.query, !query.isEmpty {
      let queryString = query
        .map { key, value in
          let encodedKey = key.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? key
          let encodedValue = value.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? value
          return "\(encodedKey)=\(encodedValue)"
        }
        .joined(separator: "&")
      let separator = url.contains("?") ? "&" : "?"
      url = "\(url)\(separator)\(queryString)"
    }

    return url
  }

  // MARK: - Artwork URL Resolution

  /// Resolves an artwork URL for a track using the configured artwork transform.
  /// Returns an ImageSource with transformed URL and headers for image loading.
  ///
  /// - Parameters:
  ///   - track: The track whose artwork URL should be transformed
  ///   - perRouteConfig: Optional per-route artwork config that overrides global config
  /// - Returns: ImageSource ready for image loading, or nil if no artwork
  func resolveArtworkUrl(track: Track, perRouteConfig: MediaRequestConfig?) async -> ImageSource? {
    // Determine effective artwork config: per-route overrides global
    let effectiveArtworkConfig = perRouteConfig ?? config.artwork

    // If no artwork config and no track.artwork, nothing to transform
    if effectiveArtworkConfig == nil, track.artwork == nil {
      return nil
    }

    // If no artwork config, return original artwork URL as simple ImageSource
    guard let artworkConfig = effectiveArtworkConfig else {
      guard let artwork = track.artwork else { return nil }
      return ImageSource(uri: artwork, method: nil, headers: nil, body: nil)
    }

    do {
      // Create base config from global request config
      var mergedConfig = RequestConfig(
        method: config.request?.method,
        path: track.artwork, // Use track.artwork as default path
        baseUrl: config.request?.baseUrl,
        headers: config.request?.headers,
        query: config.request?.query,
        body: config.request?.body,
        contentType: config.request?.contentType,
        userAgent: config.request?.userAgent
      )

      // If there's a resolve callback, call it for per-track config
      if let resolve = artworkConfig.resolve {
        let outerPromise = resolve(track)
        let innerPromise = try await outerPromise.await()
        let resolvedConfig = try await innerPromise.await()

        // Merge resolved config (extractConfig avoids Nitro bridge memory issues)
        mergedConfig = mergeRequestConfig(base: mergedConfig, override: extractConfig(resolvedConfig))
      } else {
        // No resolve callback - apply artwork static config
        let artworkStaticConfig = RequestConfig(
          method: artworkConfig.method,
          path: artworkConfig.path,
          baseUrl: artworkConfig.baseUrl,
          headers: artworkConfig.headers,
          query: artworkConfig.query,
          body: artworkConfig.body,
          contentType: artworkConfig.contentType,
          userAgent: artworkConfig.userAgent
        )
        mergedConfig = mergeRequestConfig(base: mergedConfig, override: artworkStaticConfig)
      }

      // Apply transform callback if present
      if let transform = artworkConfig.transform {
        let outerPromise = transform(mergedConfig, nil)
        let innerPromise = try await outerPromise.await()
        let transformedConfig = try await innerPromise.await()

        // Extract values immediately to avoid Nitro bridge memory issues
        mergedConfig = extractConfig(transformedConfig)
      }

      // Build final URL
      let uri = buildUrl(from: mergedConfig)

      // Build headers map, merging explicit headers with userAgent
      var headers = mergedConfig.headers ?? [:]
      if let userAgent = mergedConfig.userAgent, headers["User-Agent"] == nil {
        headers["User-Agent"] = userAgent
      }

      logger.debug("Artwork URL transformed: \(track.artwork ?? "nil") -> \(uri)")

      return ImageSource(
        uri: uri,
        method: mergedConfig.method,
        headers: headers.isEmpty ? nil : headers,
        body: mergedConfig.body
      )
    } catch {
      logger.error("Failed to transform artwork URL for track: \(track.title), error: \(error.localizedDescription)")
      // On error, return nil to avoid broken images
      return nil
    }
  }

  /// Extracts all values from a RequestConfig into a new instance to avoid
  /// memory corruption in Nitro's Swift-C++ bridge when the Promise is deallocated.
  private func extractConfig(_ config: RequestConfig) -> RequestConfig {
    RequestConfig(
      method: config.method,
      path: config.path,
      baseUrl: config.baseUrl,
      headers: config.headers,
      query: config.query,
      body: config.body,
      contentType: config.contentType,
      userAgent: config.userAgent
    )
  }

  /// Merges two RequestConfig objects, with override values taking precedence.
  private func mergeRequestConfig(base: RequestConfig, override: RequestConfig) -> RequestConfig {
    RequestConfig(
      method: override.method ?? base.method,
      path: override.path ?? base.path,
      baseUrl: override.baseUrl ?? base.baseUrl,
      headers: mergeHeaders(base.headers, override.headers),
      query: mergeQuery(base.query, override.query),
      body: override.body ?? base.body,
      contentType: override.contentType ?? base.contentType,
      userAgent: override.userAgent ?? base.userAgent
    )
  }

  // MARK: - Accessors

  func getPath() -> String {
    path
  }

  func getContent() -> ResolvedTrack? {
    content
  }

  func getTabs() -> [Track]? {
    tabs
  }
}
