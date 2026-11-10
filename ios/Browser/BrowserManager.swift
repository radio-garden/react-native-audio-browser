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
  private let contentCache = LRUCache<String, ResolvedTrack>(maxSize: 20)
  // Moves on `clearContent`, so a fetch that outlives one caches nothing; `pageGenerations` does
  // the same for one path, and is cleared only together with a move of this.
  private var contentGeneration = 0
  private var pageGenerations: [String: Int] = [:]

  // Raw results, never hydrated ones: `FavoriteSet.hydrate` skips a track whose `favorited` is
  // already set, so a hydrated slot would keep the favorites of the first search.
  private var lastSearchKey: String?
  private var lastSearchResults: [Track]?

  private var favorites = FavoriteSet()

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
      if let tabs, !TabBarEntries.same(oldValue, tabs) {
        tabsChanged.emit(tabs)
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
  /// Emitted on any change to the favorites, however it came about.
  let favoritesChanged = Emitter<Void>()

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

  func setFavorites(_ favorites: [String]) {
    if self.favorites.replace(with: favorites) {
      favoritesChanged.emit(())
    }
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

  /// Loads `path` as the current page. `bypassCache` refetches it and keeps the current content
  /// on screen meanwhile. A newer load supersedes this one: its result or error is dropped, so a
  /// slow failing /a cannot show its error over a fast /b's content.
  func load(_ path: String, bypassCache: Bool = false) async throws {
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
      let resolved = try await resolve(path, useCache: !bypassCache)
      guard navigationId == currentNavigationId else { return }
      content = resolved
    } catch {
      guard navigationId == currentNavigationId else { return }
      throw error
    }
  }

  /// Contextual paths resolve their parent page. Favorites are hydrated on every call.
  func resolve(_ path: String, useCache: Bool = true) async throws -> ResolvedTrack {
    let normalizedPath = BrowserPathHelper.stripTrackId(path)

    if useCache, let cached = contentCache.get(normalizedPath) {
      return favorites.hydrate(cached)
    }

    let generation = pageGeneration(normalizedPath)
    let resolvedTrack = try await pages.resolve(normalizedPath)

    if generation == pageGeneration(normalizedPath) {
      contentCache.set(normalizedPath, value: resolvedTrack)
    }

    return favorites.hydrate(resolvedTrack)
  }

  /// Throws on a contextual path, which never names a cache entry (Android requires the same).
  func invalidateContentCache(_ path: String) throws {
    guard !BrowserPathHelper.isContextual(path) else {
      throw BrowserError.invalidConfiguration(
        "invalidateContentCache() expects a container path, not a contextual path: \(path)",
      )
    }
    pageGenerations[path, default: 0] += 1
    contentCache.remove(path)
  }

  private func pageGeneration(_ path: String) -> (Int, Int?) {
    (contentGeneration, pageGenerations[path])
  }

  /// Clears every cache and the resolved request layers, then re-queries the tabs and reloads
  /// the current path, independently: a failed reload goes to `reloadFailed` with its path, and
  /// a failed tabs query keeps the current tabs.
  func invalidateAll(reloadFailed: @escaping @MainActor (Error, String) async -> Void) {
    clearContent()
    requests.invalidateLayers()
    Task { _ = try? await queryTabs() }
    Task {
      do {
        try await load(path, bypassCache: true)
      } catch {
        await reloadFailed(error, path)
      }
    }
  }

  /// Clears the cached pages and the last search; a fetch in flight caches nothing.
  func clearContent() {
    contentCache.clear()
    contentGeneration &+= 1
    pageGenerations.removeAll()
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

    let generation = contentGeneration
    let results = try await pages.search(params)

    if generation == contentGeneration {
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
    let generation = contentGeneration
    guard let tabs = try await pages.tabs() else { return [] }
    if generation == contentGeneration { self.tabs = tabs }
    return tabs
  }

  // MARK: - Queue Expansion (for CarPlay/external controllers)

  /// The queue a tap on the contextual `path` plays (`SectionScope.queue`); nil when `path` is
  /// not contextual.
  func expandQueueFromContextualPath(_ path: String) async throws -> (tracks: [Track], selectedIndex: Int)? {
    guard BrowserPathHelper.extractTrackId(path) != nil else { return nil }
    let resolved = try await resolve(BrowserPathHelper.stripTrackId(path), useCache: true)
    guard let sections = resolved.normalizedSections else { return nil }
    return SectionScope.queue(in: sections, path: path, singleTrack: config.singleTrack)
  }
}

// MARK: - TrackSelectionBrowser

extension BrowserManager: TrackSelectionBrowser {}
