import Foundation
import NitroModules
import os.log

@MainActor
final class BrowserManager {
  // Nonisolated so HybridAudioBrowser can create it as a property default.
  nonisolated init() {
    pages = PageResolver(requests: requests)
  }

  let logger = Logger(subsystem: "com.audiobrowser", category: "BrowserManager")

  // MARK: - Private Properties

  let requests = RequestPipeline()
  private let pages: PageResolver

  // Entries are unhydrated; `resolve` hydrates favorites on the way out.
  // A network error or a 5xx keeps a stale page; a 404 or an undecodable body drops it.
  private let contentCache = PageCache<ResolvedTrack>(maxSize: 20) { error in
    error is URLError || (error as? HttpClient.HttpException).map { $0.code >= 500 } ?? false
  }

  /// Moves on every `clearContent`, so a search or tabs query that outlives one stores nothing.
  private var clearEpoch = 0

  // Raw results, never hydrated ones: `FavoriteSet.hydrate` skips a track whose `favorited` is
  // already set, so a hydrated slot would keep the favorites of the first search.
  private var lastSearchKey: String?
  private var lastSearchResults: [Track]?

  /// The favorite set; set its `queue` to the player.
  private(set) lazy var favorites = Favorites(content: self)

  private var currentNavigationId: Int = 0

  // MARK: - Public State

  private(set) var path: String = "/" {
    didSet {
      if oldValue != path {
        pathChanged.emit(path)
      }
    }
  }

  private(set) var content: ResolvedTrack? {
    didSet {
      // ResolvedTrack is not comparable, so every set emits.
      contentChanged.emit(content)
    }
  }

  private(set) var tabs: [Track]? {
    didSet {
      // An unchanged result must not churn the CarPlay tab bar or reset its selection;
      // `invalidateAll` re-queries the tabs every time.
      if let emitted = TabBarEntries.change(from: oldValue, to: tabs) {
        tabsChanged.emit(emitted)
      }
    }
  }

  // MARK: - Configuration

  private(set) var isConfigured = false

  var config: BrowserConfig {
    get { requests.config }
    set {
      isConfigured = true
      requests.config = newValue
      configChanged.emit(newValue)
    }
  }

  // MARK: - Events (emitted on the main actor)

  let pathChanged = Emitter<String>()
  let contentChanged = Emitter<ResolvedTrack?>()
  let tabsChanged = Emitter<[Track]>()
  let configChanged = Emitter<BrowserConfig>()
  var pageExpired: Emitter<String> { contentCache.expired }
  var pageRefreshed: Emitter<String> { contentCache.refreshed }
  var favoritesChanged: Emitter<Void> { favorites.changed }

  /// Runs the app's `handleTrackLoad` and awaits both promise layers (a JS callback returning
  /// `Promise<void>` arrives as `Promise<Promise<Void>>`); a failure is logged, not thrown. False
  /// when the app set no handler.
  func awaitTrackLoadHandler(event: TrackLoadEvent) async -> Bool {
    guard let handler = config.handleTrackLoad else { return false }
    do {
      try await handler(event).awaitNested()
    } catch {
      logger.error("handleTrackLoad failed: \(error.localizedDescription)")
    }
    return true
  }

  // MARK: - Favorites

  func setFavorites(_ identities: [String]) {
    favorites.replace(with: identities)
  }

  func setFavoriteEnabled(_ enabled: Bool) {
    favorites.enabled = enabled
  }

  func setFavorited(identity: String, favorited: Bool) {
    favorites.set(identity, favorited: favorited)
  }

  func hydrateFavorite(_ track: Track) -> Track {
    favorites.hydrate(track)
  }

  // MARK: - Navigation

  /// Loads `path` as the current page and returns whether it published the content.
  /// `bypassCache` refetches it and keeps the current content on screen meanwhile. A newer load
  /// supersedes this one: its result or error is dropped, so a slow failing /a cannot show its
  /// error over a fast /b's content.
  func load(_ path: String, bypassCache: Bool = false) async throws -> Bool {
    currentNavigationId += 1
    let navigationId = currentNavigationId
    self.path = path
    if bypassCache {
      // So a failed reload doesn't leave the stale page in the cache.
      contentCache.remove(path)
    } else {
      content = nil
    }
    do {
      let resolved = try await resolve(path)
      guard navigationId == currentNavigationId else { return false }
      content = resolved
      return true
    } catch {
      guard navigationId == currentNavigationId else { return false }
      throw error
    }
  }

  /// Contextual paths resolve their parent page. Favorites are hydrated on every call.
  func resolve(_ path: String) async throws -> ResolvedTrack {
    let normalizedPath = BrowserPathHelper.stripTrackId(path)

    let resolvedTrack = try await contentCache.value(for: normalizedPath) { [pages] in
      try await pages.resolve(normalizedPath)
    }
    return favorites.hydrate(resolvedTrack)
  }

  /// Refetches the page `path` resolves to when it has expired, and returns once that settles;
  /// `pageRefreshed` tells when it replaced the page.
  func refreshPage(_ path: String) async {
    let normalizedPath = BrowserPathHelper.stripTrackId(path)
    await contentCache.revalidate(normalizedPath) { [pages] in try await pages.resolve(normalizedPath) }
  }

  /// Whether the page `path` resolves to is cached and its lifetime has passed.
  func isPageExpired(_ path: String) -> Bool {
    contentCache.isExpired(BrowserPathHelper.stripTrackId(path))
  }

  /// Throws on a contextual path, which never names a cache entry (Android requires the same).
  func invalidateContentCache(_ path: String) throws {
    guard !BrowserPathHelper.isContextual(path) else {
      throw BrowserError.invalidConfiguration(
        "invalidateContentCache() expects a container path, not a contextual path: \(path)",
      )
    }
    contentCache.remove(path)
  }

  /// Clears every cache and the resolved request layers, then re-queries the tabs; a failed
  /// query keeps the current tabs. Reloading the current path is the caller's.
  func invalidateAll() {
    clearContent()
    requests.invalidateLayers()
    Task { _ = try? await queryTabs() }
  }

  /// Clears the cached pages and the last search; a fetch in flight caches nothing.
  func clearContent() {
    contentCache.clear()
    clearEpoch += 1
    lastSearchKey = nil
    lastSearchResults = nil
  }

  /// The app's `formatNavigationError` result, or the default when it has none or it fails.
  func formatted(_ error: NavigationError, path: String) async -> FormattedNavigationError {
    let fallback = error.defaultFormatted()
    guard let formatter = config.formatNavigationError else { return fallback }
    let params = FormatNavigationErrorParams(
      error: error, defaultFormatted: fallback, path: path, kind: NavigationErrorKind(path: path),
    )
    return await (try? formatter(params).awaitOrTimeout()) ?? fallback
  }

  // MARK: - Search

  func search(_ query: String) async throws -> ResolvedTrack {
    try await search(
      SearchParams(mode: nil, query: query, genre: nil, artist: nil, album: nil, title: nil, playlist: nil, reference: .unknown),
    )
  }

  /// Cached by `cacheKey`, not `query`: voice intents fill `query` from a genre or artist when
  /// nothing was spoken, so "play jazz" and a text search for "jazz" share a `query`.
  func search(_ params: SearchParams) async throws -> ResolvedTrack {
    let query = params.query
    let cacheKey = params.cacheKey

    if cacheKey == lastSearchKey, let results = lastSearchResults {
      return makeSearchResult(query: query, results: results)
    }

    let epoch = clearEpoch
    let results = try await pages.search(params)

    if epoch == clearEpoch {
      lastSearchKey = cacheKey
      lastSearchResults = results
    }

    return makeSearchResult(query: query, results: results)
  }

  /// Playable tracks for a "play X" voice intent, drilling into a browsable-only first result
  /// (`SearchDrillIn.playable`). Mirrors Android's `searchPlayable`.
  func searchPlayable(_ params: SearchParams) async throws -> [Track]? {
    // Disabled tracks go before drill-in, so an unavailable first result can't take the play.
    let children = try await ((search(params).flattenedChildren) ?? [])
      .filter { $0.disabled != true }
    return try await SearchDrillIn.playable(from: children) { [self] path in
      logger.debug("First search result is browsable-only, resolving: \(path)")
      return try await ((resolve(path).flattenedChildren) ?? []).filter { $0.disabled != true }
    }
  }

  private func makeSearchResult(query: String, results: [Track]) -> ResolvedTrack {
    // Search results become one untitled section (ADR 0010).
    ResolvedTrack(
      path: BrowserPathHelper.createSearchPath(query),
      style: nil,
      sections: [.untitled(results.map { favorites.hydrate($0) })],
      children: nil,
      carPlaySiriListButton: nil,
      id: nil,
      src: nil,
      artwork: nil,
      artworkSource: nil, request: nil,
      title: "Search: \(query)",
      subtitle: nil,
      artist: nil,
      albumPath: nil,
      album: nil,
      description: nil,
      genre: nil,
      duration: nil,
      disabled: nil,
      favorited: nil,
      live: nil,
      icyCharset: nil,
    )
  }

  // MARK: - Tabs

  func queryTabs() async throws -> [Track] {
    let epoch = clearEpoch
    let tabs = try await pages.tabs()
    if epoch == clearEpoch { self.tabs = tabs }
    return tabs ?? []
  }

  // MARK: - Queue Expansion (for CarPlay/external controllers)

  /// The queue a tap on the contextual `path` plays (`SectionScope.queue`) and its source page
  /// (`SectionScope.expanding`); nil when `path` is not contextual.
  func expandQueueFromContextualPath(_ path: String) async throws
    -> (tracks: [Track], selectedIndex: Int, sourcePath: String)?
  {
    try await SectionScope.expanding(path) { try await self.expandScoped($0) }
  }

  private func expandScoped(_ path: String) async throws -> (tracks: [Track], selectedIndex: Int)? {
    guard BrowserPathHelper.extractTrackId(path) != nil else { return nil }
    let resolved = try await resolve(BrowserPathHelper.stripTrackId(path))
    guard let sections = resolved.normalizedSections else { return nil }
    return try SectionScope.queue(in: sections, path: path, singleTrack: config.singleTrack)
  }
}

// MARK: - TrackSelectionBrowser

extension BrowserManager: TrackSelectionBrowser {}

// MARK: - FavoriteStamped

extension BrowserManager: FavoriteStamped {
  func restampFavorites(_ restamp: (Track) -> Track?) {
    guard let restamped = content?.replacingEachTrack(restamp) else { return }
    content = restamped
  }
}
