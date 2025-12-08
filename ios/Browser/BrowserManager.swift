import Foundation
import NitroModules

/// Custom browser errors.
enum BrowserError: Error {
  case contentNotFound(path: String)
  case httpError(code: Int, body: String)
  case networkError(Error)
  case invalidConfiguration(String)

  var localizedDescription: String {
    switch self {
    case .contentNotFound(let path):
      return "No content found for path: \(path)"
    case .httpError(let code, let body):
      return "HTTP error \(code): \(body)"
    case .networkError(let error):
      return "Network error: \(error.localizedDescription)"
    case .invalidConfiguration(let message):
      return "Invalid configuration: \(message)"
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
      if let tabs = tabs {
        onTabsChanged?(tabs)
      }
    }
  }

  private func assertMainThread() {
    assert(Thread.isMainThread, "BrowserManager state must be modified on main thread")
  }

  // MARK: - Configuration

  /// Browser configuration containing routes, search, tabs, and request settings.
  var config: BrowserConfig = BrowserConfig()

  /// Callback to transform artwork URLs for tracks.
  var artworkUrlResolver: ((Track, MediaRequestConfig?) async -> ImageSource?)?

  // MARK: - Callbacks

  var onPathChanged: ((String) -> Void)?
  var onContentChanged: ((ResolvedTrack?) -> Void)?
  var onTabsChanged: (([Track]) -> Void)?

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
  private func hydrateFavorite(_ track: Track) -> Track {
    // Don't overwrite API-provided favorites
    if track.favorited != nil { return track }
    if favoriteIds.isEmpty { return track }

    guard let src = track.src, favoriteIds.contains(src) else {
      return track
    }

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
      favorited: true,
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
  @MainActor
  func navigate(_ path: String) async throws {
    self.path = path
    self.content = nil  // Clear for loading state

    let resolved = try await resolve(path, useCache: false)
    self.content = resolved
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
  @MainActor
  func refresh() async throws {
    let currentPath = path
    contentCache.remove(currentPath)
    let resolved = try await resolve(currentPath, useCache: false)
    self.content = resolved
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
    params: [String: String]
  ) async throws -> ResolvedTrack {
    // Build the request URL
    let baseUrl = config.request?.baseUrl ?? routeConfig.baseUrl
    guard let baseUrl = baseUrl else {
      throw BrowserError.invalidConfiguration("No URL configured for route")
    }

    let url = BrowserPathHelper.buildUrl(baseUrl: baseUrl, path: path)

    // Build HTTP request - convert HttpMethod enum to string
    let method = (routeConfig.method ?? config.request?.method)?.stringValue ?? "GET"
    let request = HttpClient.HttpRequest(
      url: url,
      method: method,
      headers: mergeHeaders(config.request?.headers, routeConfig.headers)
    )

    // Execute request
    let result: JsonResolvedTrack = try await httpClient.requestJson(request, as: JsonResolvedTrack.self)
    return result.toNitro()
  }

  private func mergeHeaders(
    _ base: [String: String]?,
    _ override: [String: String]?
  ) -> [String: String]? {
    guard let base = base else { return override }
    guard let override = override else { return base }
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
      if track.url == nil && track.src == nil {
        throw BrowserError.invalidConfiguration(
          "Track must have either 'url' or 'src' for stable identification: \(track.title)"
        )
      }

      var transformedTrack = track

      // Generate contextual URL for playable-only tracks (has src but no url)
      if track.src != nil && track.url == nil {
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
    // Check cache
    if query == lastSearchQuery, let results = lastSearchResults {
      return ResolvedTrack(
        url: BrowserPathHelper.createSearchPath(query),
        children: results,
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

    // Cache results
    lastSearchQuery = query
    lastSearchResults = results

    // Cache individual tracks
    results.forEach { cacheTrack($0) }

    return ResolvedTrack(
      url: BrowserPathHelper.createSearchPath(query),
      children: results,
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

    self.tabs = tabTracks
    return tabTracks
  }

  // MARK: - Queue Expansion (for CarPlay/external controllers)

  /// Expands a contextual URL to a full queue of playable tracks.
  func expandQueueFromContextualUrl(_ url: String) async throws -> (tracks: [Track], selectedIndex: Int)? {
    guard BrowserPathHelper.isContextual(url) else { return nil }

    let parentPath = BrowserPathHelper.stripTrackId(url)
    guard let trackId = BrowserPathHelper.extractTrackId(url) else { return nil }

    // Resolve the parent container
    let resolved = try await resolve(parentPath, useCache: true)
    guard let children = resolved.children else { return nil }

    // Filter to playable tracks (have src)
    let playableTracks = children.filter { $0.src != nil }
    guard !playableTracks.isEmpty else { return nil }

    // Find selected track index
    let selectedIndex = playableTracks.firstIndex { $0.src == trackId } ?? 0

    // If singleTrack mode, return just the selected track
    if config.singleTrack {
      return (tracks: [playableTracks[selectedIndex]], selectedIndex: 0)
    }

    return (tracks: playableTracks, selectedIndex: selectedIndex)
  }

  // MARK: - Accessors

  func getPath() -> String {
    return path
  }

  func getContent() -> ResolvedTrack? {
    return content
  }

  func getTabs() -> [Track]? {
    return tabs
  }
}
