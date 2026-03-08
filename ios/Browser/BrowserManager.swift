import Foundation
import NitroModules
import os.log

/// Custom browser errors.
enum BrowserError: Error {
  case contentNotFound(path: String)
  case httpError(code: Int, body: String)
  case networkError(Error)
  case invalidConfiguration(String)
  case callbackError(String)

  var localizedDescription: String {
    switch self {
    case .contentNotFound:
      return "Content not found"
    case let .httpError(code, body):
      // Try to extract error message from JSON response: { "error": "message" }
      if let data = body.data(using: .utf8),
         let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
         let errorMessage = json["error"] as? String
      {
        return errorMessage
      }
      // Fall back to localized HTTP status description
      return HTTPURLResponse.localizedString(forStatusCode: code)
    case let .networkError(error):
      return "Network error: \(error.localizedDescription)"
    case let .invalidConfiguration(message):
      return "Invalid configuration: \(message)"
    case let .callbackError(message):
      return message
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
@MainActor
final class BrowserManager {
  // Allow creation from nonisolated contexts (e.g. HybridAudioBrowser property default)
  nonisolated init() {}

  let logger = Logger(subsystem: "com.audiobrowser", category: "BrowserManager")

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

  private(set) var path: String = "/" {
    didSet {
      if oldValue != path {
        onPathChanged?(path)
      }
    }
  }

  private(set) var content: ResolvedTrack? {
    didSet {
      // Note: Can't compare ResolvedTrack directly, always fire callback
      onContentChanged?(content)
    }
  }

  private(set) var tabs: [Track]? {
    didSet {
      if let tabs {
        onTabsChanged?(tabs)
      }
    }
  }

  // MARK: - Configuration

  /// Whether configureBrowser() has been called
  private(set) var isConfigured = false

  /// Browser configuration containing routes, search, tabs, and request settings.
  var config: BrowserConfig = .init() {
    didSet {
      isConfigured = true
      onConfigChanged?(config)
    }
  }

  // MARK: - Callbacks

  var onPathChanged: ((String) -> Void)?
  var onContentChanged: ((ResolvedTrack?) -> Void)?
  var onTabsChanged: (([Track]) -> Void)?
  var onConfigChanged: ((BrowserConfig) -> Void)?

  /// Forwards to config.awaitTrackLoadHandler so callers don't need to
  /// cross the MainActor boundary to access `config`.
  func awaitTrackLoadHandler(event: TrackLoadEvent) async -> Bool {
    // awaitTrackLoadHandler manages its own MainActor dispatch internally
    nonisolated(unsafe) let cfg = config
    nonisolated(unsafe) let evt = event
    return await cfg.awaitTrackLoadHandler(event: evt)
  }

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

    return track.copying(favorited: isFavorited)
  }

  /// Hydrates favorites on all children of a ResolvedTrack.
  private func hydrateChildren(_ resolvedTrack: ResolvedTrack) -> ResolvedTrack {
    guard let children = resolvedTrack.children else { return resolvedTrack }
    let hydratedChildren = children.map { hydrateFavorite($0) }
    return resolvedTrack.copying(children: hydratedChildren)
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
  func navigate(_ path: String) async throws {
    // Increment navigation ID and capture for this navigation
    currentNavigationId += 1
    let navigationId = currentNavigationId

    self.path = path
    content = nil // Clear for loading state

    let resolved = try await resolve(path)

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
    if let children = resolvedTrack.children {
      let transformed = try await transformChildren(children, parentPath: path, routeEntry: routeEntry)
      return resolvedTrack.copying(children: transformed)
    }

    return resolvedTrack
  }

  // MARK: - Route Resolution

  private func findBestRouteMatch(
    path: String,
    routes: [NativeRouteEntry],
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
    params: [String: String],
  ) async throws -> ResolvedTrack {
    // Priority: callback > config > static
    if let callback = entry.browseCallback {
      let callbackParam = BrowserSourceCallbackParam(path: path, routeParams: params)
      let outerPromise = callback(callbackParam)
      let innerPromise = try await outerPromise.await()
      let result = try await innerPromise.await()

      // Handle the BrowseResult union type
      switch result {
      case let .first(resolvedTrack):
        return resolvedTrack
      case let .second(browseError):
        throw BrowserError.callbackError(browseError.error)
      }
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
    params _: [String: String],
  ) async throws -> ResolvedTrack {
    // Build the request URL - route config takes precedence over global config
    let baseUrl = routeConfig.baseUrl ?? config.request?.baseUrl
    guard let baseUrl else {
      throw BrowserError.invalidConfiguration("No URL configured for route")
    }

    var url = BrowserPathHelper.buildUrl(baseUrl: baseUrl, path: path)

    // Add query parameters from config - route config values override global config
    if let queryParams = mergeDicts(config.request?.query, routeConfig.query) {
      url = BrowserPathHelper.appendQuery(queryParams, to: url)
    }

    // Build HTTP request - route config takes precedence
    let method = (routeConfig.method ?? config.request?.method)?.stringValue ?? "GET"
    let request = HttpClient.HttpRequest(
      url: url,
      method: method,
      headers: mergeDicts(config.request?.headers, routeConfig.headers),
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

  /// Merges two optional dictionaries, with override values taking precedence.
  func mergeDicts(
    _ base: [String: String]?,
    _ override: [String: String]?,
  ) -> [String: String]? {
    guard let base else { return override }
    guard let override else { return base }
    return base.merging(override) { _, new in new }
  }

  // MARK: - Child Transformation

  private func transformChildren(
    _ children: [Track],
    parentPath: String,
    routeEntry: NativeRouteEntry,
  ) async throws -> [Track] {
    var transformed: [Track] = []

    for track in children {
      // Validate track has stable identifier
      if track.url == nil, track.src == nil {
        throw BrowserError.invalidConfiguration(
          "Track must have either 'url' or 'src' for stable identification: \(track.title)",
        )
      }

      var transformedTrack = track

      if track.src != nil, track.url == nil {
        let contextualUrl = BrowserPathHelper.build(parentPath: parentPath, trackId: track.src!)
        transformedTrack = track.copying(url: contextualUrl)
      }

      // Resolve artwork URL at browse-time (no size context)
      let artworkConfig = routeEntry.artwork ?? config.artwork
      if let imageSource = await resolveArtworkUrl(track: transformedTrack, perRouteConfig: artworkConfig) {
        transformedTrack = transformedTrack.copying(artworkSource: imageSource)
      }

      // Resolve artwork for image row items
      if let imageRowItems = transformedTrack.imageRow {
        var resolvedItems: [ImageRowItem] = []
        for item in imageRowItems {
          let itemTrack = Track(
            url: item.url,
            src: nil,
            artwork: item.artwork,
            artworkSource: nil,
            artworkCarPlayTinted: nil,
            title: item.title,
            subtitle: nil,
            artist: nil,
            album: nil,
            description: nil,
            genre: nil,
            duration: nil,
            style: nil,
            childrenStyle: nil,
            favorited: nil,
            groupTitle: nil,
            live: nil,
            imageRow: nil,
          )
          let itemImageSource = await resolveArtworkUrl(track: itemTrack, perRouteConfig: artworkConfig)
          resolvedItems.append(ImageRowItem(
            url: item.url,
            artwork: item.artwork,
            artworkSource: itemImageSource,
            title: item.title,
          ))
        }
        transformedTrack = transformedTrack.copying(imageRow: resolvedItems)
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
      return makeSearchResult(query: query, results: hydratedResults)
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
      playlist: nil,
    )

    var results: [Track]

    if let callback = searchEntry.searchCallback {
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

    return makeSearchResult(query: query, results: hydratedResults)
  }

  private func makeSearchResult(query: String, results: [Track]) -> ResolvedTrack {
    ResolvedTrack(
      url: BrowserPathHelper.createSearchPath(query),
      children: results,
      carPlaySiriListButton: nil,
      src: nil,
      artwork: nil,
      artworkSource: nil,
      artworkCarPlayTinted: nil,
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
      groupTitle: nil,
      live: nil,
      imageRow: nil,
    )
  }

  // MARK: - Tabs

  /// Query navigation tabs.
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

// MARK: - TrackSelectionBrowser

extension BrowserManager: TrackSelectionBrowser {}
