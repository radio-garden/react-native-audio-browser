import Foundation
import NitroModules
import os.log

/// Custom browser errors.
enum BrowserError: LocalizedError {
  case contentNotFound(path: String)
  case invalidConfiguration(String)
  case callbackError(String)

  var errorDescription: String? {
    switch self {
    case .contentNotFound: "Content not found"
    case let .invalidConfiguration(message): "Invalid configuration: \(message)"
    case let .callbackError(message): message
    }
  }
}

/// The browser's state (path, content, tabs, config), its content and search caches, favorites
/// and events. Pages come from `PageResolver`; requests go through `RequestPipeline`.
@MainActor
final class BrowserManager {
  // Allow creation from nonisolated contexts (e.g. HybridAudioBrowser property default)
  nonisolated init() {
    pages = PageResolver(requests: requests)
  }

  let logger = Logger(subsystem: "com.audiobrowser", category: "BrowserManager")

  // MARK: - Private Properties

  let requests = RequestPipeline()
  private let pages: PageResolver

  // LRU cache for resolved content - keyed by path
  private let contentCache = LRUCache<String, ResolvedTrack>(maxSize: 20)

  // Most recent search, keyed by `searchCacheKey`.
  private var lastSearchKey: String?
  private var lastSearchResults: [Track]?

  // Set of favorited track identities (id when non-blank, else src).
  private var favoriteIds: Set<String> = []

  // Whether favoriting is enabled, propagated from the player's `favorite`
  // capability. false = no row hearts. Set via setFavoriteEnabled.
  private var favoriteEnabled = false

  // Navigation tracking to prevent race conditions
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
      // Note: Can't compare ResolvedTrack directly, always fire callback
      contentChanged.emit(content)
    }
  }

  private(set) var tabs: [Track]? {
    didSet {
      // Emit only on a real change: invalidateAllContent() re-queries the tabs
      // source on every invalidation (a callback source can re-resolve, e.g.
      // titles in a new locale), and an unchanged result must not churn the
      // CarPlay tab bar or reset its selection.
      if let tabs, !TabBarEntries.same(oldValue, tabs) {
        tabsChanged.emit(tabs)
      }
    }
  }

  // MARK: - Configuration

  /// Whether configureBrowser() has been called
  private(set) var isConfigured = false

  /// Browser configuration containing routes, search, tabs, and request settings. Kept on
  /// `requests`, which every request reads.
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
  /// The favorite id set changed, however it changed (e.g. from the app or a webview), so
  /// surfaces like the CarPlay now-playing heart can re-render.
  let favoritesChanged = Emitter<Void>()

  /// Runs the app's `handleTrackLoad` for `event` and waits for it, both promise layers: a JS
  /// callback returning `Promise<void>` arrives as `Promise<Promise<Void>>`. False when the app
  /// set none.
  func awaitTrackLoadHandler(event: TrackLoadEvent) async -> Bool {
    guard let handler = config.handleTrackLoad else { return false }
    do {
      try await handler(event).await().await()
    } catch {
      logger.error("handleTrackLoad failed: \(error.localizedDescription)")
    }
    return true
  }

  // MARK: - Favorites

  /// Sets the favorited track identifiers.
  func setFavorites(_ favorites: [String]) {
    let newIds = Set(favorites)
    guard newIds != favoriteIds else { return }
    favoriteIds = newIds
    favoritesChanged.emit(())
  }

  /// Enables/disables favoriting (propagated from the `favorite` capability).
  /// false disables row-heart hydration.
  func setFavoriteEnabled(_ enabled: Bool) {
    favoriteEnabled = enabled
  }

  /// Optimistically reflects a local favorite toggle in the favorite set, keyed
  /// by the track's identity (id when non-blank, else src). The consumer
  /// reconciles `favoriteIds` to its canonical set on the next `setFavorites`;
  /// `hydrateFavorite` gives the same answer before and after, so the
  /// now-playing heart stays responsive.
  func setFavorited(identity: String, favorited: Bool) {
    if favorited {
      favoriteIds.insert(identity)
    } else {
      favoriteIds.remove(identity)
    }
  }

  /// Hydrates the favorited field on a track based on the favoriteIds set.
  /// No-op unless favoriting is enabled (the `favorite` capability). Only
  /// identity-bearing tracks (id or src) are favoritable; the flag is set to
  /// true OR false so non-favorited tracks still show an (empty) heart. A
  /// caller-set `favorited` wins — API/consumer-provided values are never
  /// overwritten (matches Android and web).
  func hydrateFavorite(_ track: Track) -> Track {
    // `identity` bridges a std::string out of the C++ track, so it is bound
    // once here rather than computed again to answer "is it favorited".
    guard favoriteEnabled, track.favorited == nil, let identity = track.identity else { return track }
    return track.copying(favorited: favoriteIds.contains(identity))
  }

  /// Hydrates favorites on all tracks of a resolved page. Pages reach
  /// hydration normalized (ADR 0010), so `sections` is the only structure
  /// to walk.
  private func hydrateChildren(_ resolvedTrack: ResolvedTrack) -> ResolvedTrack {
    guard let sections = resolvedTrack.sections else { return resolvedTrack }
    return resolvedTrack.copying(sections: sections.map { section in
      section.copying(children: section.children.map { hydrateFavorite($0) })
    })
  }

  // MARK: - Navigation

  /// Loads `path` as the current page. `bypassCache` reloads it from the network and keeps the
  /// content on screen meanwhile, for refreshing the current path. A newer load supersedes this one, and its
  /// result or failure is dropped: every caller reports a throw, so a slow /a failing after a
  /// fast /b succeeded would otherwise show /a's error over /b's content.
  func load(_ path: String, bypassCache: Bool = false) async throws {
    currentNavigationId += 1
    let navigationId = currentNavigationId
    self.path = path
    if bypassCache {
      // A failed reload must not leave the stale page to be served from the cache.
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

  /// Resolves content for a path with optional caching.
  func resolve(_ path: String, useCache: Bool = true) async throws -> ResolvedTrack {
    // Strip __trackId from contextual URLs
    let normalizedPath = BrowserPathHelper.stripTrackId(path)

    // Check content cache first
    if useCache, let cached = contentCache.get(normalizedPath) {
      return hydrateChildren(cached)
    }

    let resolvedTrack = try await pages.resolve(normalizedPath)

    // Cache the resolved content for future navigation
    contentCache.set(normalizedPath, value: resolvedTrack)

    return hydrateChildren(resolvedTrack)
  }

  /// Invalidates the content cache for a specific path.
  ///
  /// Expects a *container* path. A contextual path (`…?__trackId=…`) addresses
  /// a child, not a cache entry — entries are keyed by the stripped parent — so
  /// passing one used to remove nothing at all, silently. Android `require`s the
  /// same precondition; this throws so the misuse surfaces identically on both
  /// platforms instead of no-opping on one.
  func invalidateContentCache(_ path: String) throws {
    guard !BrowserPathHelper.isContextual(path) else {
      throw BrowserError.invalidConfiguration(
        "invalidateContentCache() expects a container path, not a contextual path: \(path)",
      )
    }
    contentCache.remove(path)
  }

  /// Clears the entire content cache (e.g. on a locale change).
  /// Runs from `invalidateAllContent`, so re-resolve the layer thunks too.
  func clearContentCache() {
    contentCache.clear()
    requests.invalidateLayers()
    // The search slot is content too: a locale switch or a re-auth changes
    // titles, src, artwork and availability, and nothing else ever evicts it,
    // so a repeated query returned pre-invalidation results forever.
    lastSearchKey = nil
    lastSearchResults = nil
  }

  /// `error` as the app's `formatNavigationError` displays it, or its default display when
  /// there is no formatter or it fails.
  func formatted(_ error: NavigationError, path: String) async -> FormattedNavigationError {
    let fallback = error.defaultFormatted()
    guard let formatter = config.formatNavigationError else { return fallback }
    let params = FormatNavigationErrorParams(error: error, defaultFormatted: fallback, path: path)
    return await (try? formatter(params).await()) ?? fallback
  }

  // MARK: - Search

  /// Execute a search query.
  /// Plain free-text search — wraps the structured overload with no mode/filters.
  func search(_ query: String) async throws -> ResolvedTrack {
    try await search(
      SearchParams(mode: nil, query: query, genre: nil, artist: nil, album: nil, title: nil, playlist: nil, reference: .unknown),
    )
  }

  /// The cache identity of a search. Sorted so key order is stable, and
  /// `\u{1}`-delimited so two different param sets can't serialize alike.
  private static func searchCacheKey(_ params: SearchParams) -> String {
    PageResolver.searchQueryParams(params)
      .sorted { $0.key < $1.key }
      .map { "\($0.key)=\($0.value)" }
      .joined(separator: "\u{1}")
  }

  /// Structured search, cached by the full parameter set.
  ///
  /// Voice intents reuse `query` across different structures —
  /// `MediaIntentCriteria` falls back to the structured value when there is no
  /// spoken name, so "play jazz" (genre) and a text search for "jazz" arrive
  /// with an identical `query` — hence `searchCacheKey` rather than `query`.
  func search(_ params: SearchParams) async throws -> ResolvedTrack {
    let query = params.query
    let cacheKey = Self.searchCacheKey(params)

    // Check cache - hydrate favorites on the way out, since they may have
    // changed since the search ran (the slot holds the raw results for exactly
    // this reason — see below).
    if cacheKey == lastSearchKey, let results = lastSearchResults {
      return makeSearchResult(query: query, results: results)
    }

    let results = try await pages.search(params)

    // Cache the RAW results, never the hydrated ones: hydration is one-shot
    // (`hydrateFavorite` skips a track whose `favorited` is already set), so a
    // hydrated slot would serve the favorites as they were at first search
    // forever. The content cache keys on the same rule — it stores the
    // unhydrated resolve and hydrates in `resolve()`.
    lastSearchKey = cacheKey
    lastSearchResults = results

    return makeSearchResult(query: query, results: results)
  }

  /// Search and return playable tracks for "play «X»" voice intents, drilling
  /// into a browsable-only first result (place/genre page) so we play its first
  /// station. Decision in `SearchDrillIn.playable`; mirrors Android's
  /// `searchPlayable`.
  func searchPlayable(_ params: SearchParams) async throws -> [Track]? {
    // Voice search never matches a disabled track (Track.disabled) — filtered
    // before drill-in so an unavailable first result can't capture the play.
    let children = try await ((search(params).flattenedChildren) ?? [])
      .filter { $0.disabled != true }
    return try await SearchDrillIn.playable(from: children) { [self] path in
      logger.debug("First search result is browsable-only, resolving: \(path)")
      return try await ((resolve(path).flattenedChildren) ?? []).filter { $0.disabled != true }
    }
  }

  /// Wraps raw search results as a resolved page, hydrating favorites on the
  /// way out — the single exit for `search`, so an unhydrated search page can't
  /// be emitted (the same funnel `resolve()` gets from `hydrateChildren`).
  private func makeSearchResult(query: String, results: [Track]) -> ResolvedTrack {
    // Search results are a flat list; as a resolved page they are one
    // untitled section (ADR 0010).
    ResolvedTrack(
      path: BrowserPathHelper.createSearchPath(query),
      style: nil,
      sections: [.untitled(results.map { hydrateFavorite($0) })],
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

  /// Query navigation tabs.
  func queryTabs() async throws -> [Track] {
    guard let tabs = try await pages.tabs() else { return [] }
    self.tabs = tabs
    return tabs
  }

  // MARK: - Queue Expansion (for CarPlay/external controllers)

  /// Expands a contextual path to a full queue of playable tracks.
  func expandQueueFromContextualPath(_ path: String) async throws -> (tracks: [Track], selectedIndex: Int)? {
    guard BrowserPathHelper.isContextual(path) else { return nil }

    let parentPath = BrowserPathHelper.stripTrackId(path)
    guard let trackId = BrowserPathHelper.extractTrackId(path) else { return nil }

    logger.debug("Expanding queue from contextual path")
    logger.debug("  path: \(path)")
    logger.debug("  parentPath: \(parentPath)")
    logger.debug("  trackId: \(trackId)")

    // Resolve the parent container
    let resolved = try await resolve(parentPath, useCache: true)
    guard let sections = resolved.normalizedSections else { return nil }

    // Queue scope is the tapped section, not the whole page (ADR 0006). The
    // stamped flat index pins which section (and which copy) was tapped when
    // the same identity appears more than once; it is only a tie-breaker —
    // a stale index falls back to the first identity match. An id that no
    // longer appears on the page at all aborts the expansion — the caller
    // falls back to the stored single track (matching Android); silently
    // queueing the changed list would resume the wrong station.
    let tappedIndex = BrowserPathHelper.extractIndex(path)
    guard let scoped = SectionScope.scoped(in: sections, containing: trackId, tappedIndex: tappedIndex) else {
      return nil
    }
    let sectionTracks = scoped.section.children
    let tappedOffset = scoped.tappedOffset

    // Filter to playable tracks (have src). A disabled track is unavailable —
    // queue expansion excludes it, so auto-advance never meets one.
    let playableTracks = sectionTracks.filter { $0.src != nil && $0.disabled != true }
    guard !playableTracks.isEmpty else { return nil }

    logger.debug("Found \(playableTracks.count) playable tracks")
    for (index, track) in playableTracks.enumerated() {
      logger.debug("  [\(index)] \(track.title) - src: \(track.src ?? "nil")")
    }

    // Find selected track index: the pinned copy when the stamp survived,
    // else the first identity match. A miss must abort rather than silently
    // start the queue at the wrong track (unreachable in practice — the
    // section was found BY this id).
    let selectedIndex: Int
    if let tappedOffset, sectionTracks[tappedOffset].src != nil, sectionTracks[tappedOffset].disabled != true {
      selectedIndex =
        sectionTracks.prefix(through: tappedOffset)
          .count(where: { $0.src != nil && $0.disabled != true }) - 1
    } else if let index = playableTracks.firstIndex(where: { $0.identity == trackId }) {
      selectedIndex = index
    } else {
      return nil
    }
    logger.debug("Selected track index: \(selectedIndex)")

    // If singleTrack mode, return just the selected track
    if config.singleTrack {
      return (tracks: [playableTracks[selectedIndex]], selectedIndex: 0)
    }

    return (tracks: playableTracks, selectedIndex: selectedIndex)
  }
}

// MARK: - TrackSelectionBrowser

extension BrowserManager: TrackSelectionBrowser {}
