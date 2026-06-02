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

  // Favorite match mode, propagated from the player's `favorite` capability.
  // nil = favoriting disabled (no row hearts). Set via setFavoriteMatch.
  private var favoriteMatch: FavoritesMatchMode?

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
  /// Fired when the favorite id set changes (e.g. an app/webview-originated
  /// favorite). Lets surfaces like the CarPlay now-playing heart re-render —
  /// they otherwise only learn of their own toggles via favorite/active-track
  /// events, never an external change routed through `setFavorites`.
  var onFavoritesChanged: (() -> Void)?

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
    let newIds = Set(favorites)
    guard newIds != favoriteIds else { return }
    favoriteIds = newIds
    onFavoritesChanged?()
  }

  /// Sets the favorite match mode (propagated from the `favorite` capability).
  /// nil disables row-heart hydration.
  func setFavoriteMatch(_ match: FavoritesMatchMode?) {
    favoriteMatch = match
  }

  /// Optimistically reflects a local favorite toggle for `src` in the match set,
  /// needing no consumer-specific id extraction: favoriting inserts `src` (which
  /// matches itself under either mode). Removal mirrors `isFavorite`'s match
  /// semantics — partial drops every id that is a path segment of `src` (the
  /// channel uid, and self-heals stray ids); exact (or disabled) drops only
  /// `src`, so unrelated exact favorites that happen to be a segment of `src`
  /// aren't collaterally lost. The consumer reconciles `favoriteIds` to its
  /// canonical ids on the next `setFavorites`; `isFavorite(src:)` gives the same
  /// answer before and after, so the now-playing heart stays responsive.
  func setFavorited(src: String, favorited: Bool) {
    if favorited {
      favoriteIds.insert(src)
    } else if favoriteMatch == .partial {
      favoriteIds = favoriteIds.filter { !BrowserPathHelper.containsSegment(src, $0) }
    } else {
      favoriteIds.remove(src)
    }
  }

  /// Hydrates the favorited field on a track based on the favoriteIds set.
  /// No-op unless favoriting is enabled (the `favorite` capability). Only
  /// playable (src-bearing) tracks are favoritable; the flag is set to true OR
  /// false so non-favorited tracks still show an (empty) heart. Local
  /// favoriteIds take precedence over API-provided values.
  func hydrateFavorite(_ track: Track) -> Track {
    guard let match = favoriteMatch, let src = track.src else { return track }

    let isFavorited = isFavorite(src: src, match: match)

    // Only create a new track if the favorited state differs
    if track.favorited == isFavorited { return track }

    return track.copying(favorited: isFavorited)
  }

  /// Whether `src` is favorited under the given match mode.
  private func isFavorite(src: String, match: FavoritesMatchMode) -> Bool {
    switch match {
    case .exact:
      return favoriteIds.contains(src)
    case .partial:
      return favoriteIds.contains { BrowserPathHelper.containsSegment(src, $0) }
    }
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

  /// Clears the entire content cache (e.g. on a locale change).
  func clearContentCache() {
    contentCache.clear()
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
    // Match an explicit route (or the '*' default). With no match, fall back to
    // the implicit default: fetch the path via the request + browse config.
    let routeEntry = (config.routes).flatMap { findBestRouteMatch(path: path, routes: $0) }

    let resolvedTrack: ResolvedTrack
    if let (entry, routeMatch) = routeEntry {
      resolvedTrack = try await resolveRouteEntry(entry, path: path, params: routeMatch.params)
    } else {
      // Implicit default — no route config, just request → browse → fetch path.
      resolvedTrack = try await resolveFromConfig(
        kind: config.browse, nil, path: path, params: ["path": path]
      )
    }

    // Validate and transform children
    if let children = resolvedTrack.children {
      let transformed = try await transformChildren(
        children, parentPath: path, routeEntry: routeEntry?.0
      )
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
      return try await resolveFromConfig(
        kind: config.browse, browseConfig, path: path, params: params
      )
    }

    if let staticContent = entry.browseStatic {
      return staticContent
    }

    throw BrowserError.contentNotFound(path: path)
  }

  private func resolveFromConfig(
    kind kindConfig: TransformableRequestConfig?,
    _ routeConfig: TransformableRequestConfig?,
    path: String,
    params: [String: String],
  ) async throws -> ResolvedTrack {
    // Layered request: request (shared) → kind (browse/search/…) → route. Each
    // layer's transform receives the previous layer's output; a layer with no
    // transform merges its static fields. `routeConfig` is nil for the implicit
    // browse default; `kindConfig` is nil for kinds with no per-kind config.
    var merged = RequestConfig(
      method: nil, path: path, baseUrl: nil, headers: nil,
      query: nil, body: nil, contentType: nil, userAgent: nil,
    )
    merged = try await applyLayer(config.request, to: merged, params: params)
    merged = try await applyLayer(kindConfig, to: merged, params: params)
    merged = try await applyLayer(routeConfig, to: merged, params: params)

    // Build the URL and execute.
    guard let baseUrl = merged.baseUrl else {
      throw BrowserError.invalidConfiguration("No URL configured for route")
    }
    var url = BrowserPathHelper.buildUrl(baseUrl: baseUrl, path: merged.path ?? path)
    if let query = merged.query, !query.isEmpty {
      url = BrowserPathHelper.appendQuery(query, to: url)
    }

    let request = HttpClient.HttpRequest(
      url: url,
      method: merged.method?.stringValue ?? "GET",
      headers: merged.headers,
      body: merged.body,
      contentType: merged.contentType ?? HttpClient.defaultContentType,
      userAgent: merged.userAgent ?? HttpClient.defaultUserAgent,
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

  /// Applies one request-config layer (request / browse / route) onto a base.
  /// A layer with a transform wins completely (it receives the base and returns
  /// the result); otherwise the layer's static fields merge over the base.
  /// `path` is carried from the base (only a transform may change it).
  func applyLayer(
    _ layer: TransformableRequestConfig?,
    to base: RequestConfig,
    params: [String: String],
  ) async throws -> RequestConfig {
    guard let layer else { return base }
    if let transform = layer.transform {
      return try await applyTransform(transform, request: base, params: params)
    }
    return RequestConfig(
      method: layer.method ?? base.method,
      path: base.path,
      baseUrl: layer.baseUrl ?? base.baseUrl,
      headers: mergeDicts(base.headers, layer.headers),
      query: mergeDicts(base.query, layer.query),
      body: layer.body ?? base.body,
      contentType: layer.contentType ?? base.contentType,
      userAgent: layer.userAgent ?? base.userAgent,
    )
  }

  /// Invokes a request `transform` (global or per-route) and copies the result
  /// out of the Nitro bridge immediately to avoid use-after-free when the
  /// `Promise<RequestConfig>` is deallocated.
  func applyTransform(
    _ transform: @escaping (RequestConfig, [String: String]?) -> Promise<Promise<RequestConfig>>,
    request: RequestConfig,
    params: [String: String],
  ) async throws -> RequestConfig {
    let outerPromise = transform(request, params)
    let innerPromise = try await outerPromise.await()
    let result = try await innerPromise.await()
    return RequestConfig(
      method: result.method,
      path: result.path,
      baseUrl: result.baseUrl,
      headers: result.headers,
      query: result.query,
      body: result.body,
      contentType: result.contentType,
      userAgent: result.userAgent,
    )
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
    routeEntry: NativeRouteEntry?,
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
      let artworkConfig = routeEntry?.artwork ?? config.artwork
      if let imageSource = await resolveArtworkUrl(track: transformedTrack, perRouteConfig: artworkConfig) {
        transformedTrack = transformedTrack.copying(artworkSource: imageSource)
      }

      // Resolve artwork for image row items
      if let imageRowItems = transformedTrack.imageRow {
        var resolvedItems: [ImageRowItem] = []
        for item in imageRowItems {
          let itemTrack = Track(
            id: nil,
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
      // Search is its own kind — request → search route (no browse layer).
      let resolved = try await resolveFromConfig(
        kind: nil, searchConfig, path: "/__search", params: ["q": query]
      )
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
      id: nil,
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
