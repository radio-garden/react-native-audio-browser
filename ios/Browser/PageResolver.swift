import Foundation
import NitroModules
import os.log

/// Turns a path into its page: matches the route, resolves its source (callback, config or
/// static content), then normalizes and stamps the result (contextual paths, browse-time
/// artwork). Also resolves the tabs and search routes.
@MainActor
final class PageResolver {
  let logger = Logger(subsystem: "com.audiobrowser", category: "PageResolver")
  private let requests: RequestPipeline

  private var config: BrowserConfig {
    requests.config
  }

  nonisolated init(requests: RequestPipeline) {
    self.requests = requests
  }

  /// The page at `path`, uncached and without favorites hydrated.
  func resolve(_ path: String) async throws -> ResolvedTrack {
    // Match an explicit route (or the '*' default). With no match, fall back to
    // the implicit default: fetch the path via the request + browse config.
    let routeEntry = config.routes.flatMap { SimpleRouter.browseRoute(for: path, in: $0) }

    let resolvedTrack: ResolvedTrack = if let (entry, routeMatch) = routeEntry {
      try await resolveRouteEntry(entry, path: path, params: routeMatch.params)
    } else {
      // Implicit default — no route config, just request → browse → fetch path.
      try await requests.fetchPage(
        nil, path: path, params: ["path": path],
      )
    }

    // Normalize to the canonical sectioned shape (children sugar → one
    // untitled section — ADR 0010), then validate and transform. The output
    // never carries `children`.
    if let sections = resolvedTrack.normalizedSections {
      // Dev diagnostic (ADR 0011) — before the blocks are folded, while each
      // level is still attributable. Every page passes here whatever its
      // source, and only on a cache miss. No-op outside debug builds.
      InertStyleDiagnostic.warn(path: path, pageStyle: resolvedTrack.style, sections: sections)
      let transformed = try await transformSections(
        sections, parentPath: path, routeEntry: routeEntry?.0,
      )
      return resolvedTrack.copying(sections: transformed, children: .some(nil))
    }

    return resolvedTrack
  }

  /// The tabs: the `__tabs__` route's page, flattened. Nil when there is no such route.
  func tabs() async throws -> [Track]? {
    guard let tabsEntry = config.routes?.first(where: { $0.path == SimpleRouter.tabsRoutePath }) else { return nil }
    let resolved = try await resolveRouteEntry(tabsEntry, path: SimpleRouter.tabsRoutePath, params: [:])
    // Tabs are a flat list, not a page: a sectioned tabs source flattens.
    return resolved.flattenedChildren ?? []
  }

  /// The `__search__` route's results for `params`, unhydrated.
  func search(_ params: SearchParams) async throws -> [Track] {
    guard let searchEntry = config.routes?.first(where: { $0.path == SimpleRouter.searchRoutePath }) else {
      throw BrowserError.contentNotFound(path: SimpleRouter.searchRoutePath)
    }
    if let callback = searchEntry.searchCallback {
      return try await callback(params).await().await()
    }
    if let searchConfig = searchEntry.searchConfig {
      return try await requests.fetchSearch(searchConfig, params: params)
    }
    throw BrowserError.contentNotFound(path: SimpleRouter.searchRoutePath)
  }

  /// The query params a structured search sends: `q` plus whichever filters are
  /// set. Mirrors Android's `executeSearchApiRequest` and the web stub.
  ///
  /// Single source of truth for "what distinguishes one search from another" —
  /// the request sends exactly this, and the cache keys on exactly this, so a
  /// field added here can't be forgotten in one place and silently serve one
  /// search's results for another.
  static func searchQueryParams(_ params: SearchParams) -> [String: String] {
    var query: [String: String] = ["q": params.query]
    if let mode = params.mode { query["mode"] = mode.stringValue }
    if params.reference == .my { query["reference"] = "my" }
    if let genre = params.genre { query["genre"] = genre }
    if let artist = params.artist { query["artist"] = artist }
    if let album = params.album { query["album"] = album }
    if let title = params.title { query["title"] = title }
    if let playlist = params.playlist { query["playlist"] = playlist }
    return query
  }

  // MARK: - Route Resolution

  private func resolveRouteEntry(
    _ entry: NativeRouteEntry,
    path: String,
    params: [String: String],
  ) async throws -> ResolvedTrack {
    // Priority: callback > config > static
    if let callback = entry.browseCallback {
      let callbackParam = BrowserSourceCallbackParam(path: path, routeParams: params)
      // BrowserSourceCallback may return a BrowseResult synchronously or via a
      // Promise. Nitro flattens (ResolvedTrack | BrowseError) | Promise<BrowseResult>
      // into a 3-arm variant: sync track, sync error, or a Promise resolving to a
      // BrowseResult (which is itself a ResolvedTrack | BrowseError variant).
      switch try await callback(callbackParam).await() {
      case let .first(resolvedTrack):
        return resolvedTrack
      case let .second(browseError):
        throw BrowserError.callbackError(browseError.error)
      case let .third(promise):
        switch try await promise.await() {
        case let .first(resolvedTrack):
          return resolvedTrack
        case let .second(browseError):
          throw BrowserError.callbackError(browseError.error)
        }
      }
    }

    if let browseConfig = entry.browseConfig {
      return try await requests.fetchPage(
        browseConfig, path: path, params: params,
      )
    }

    if let staticContent = entry.browseStatic {
      return staticContent
    }

    throw BrowserError.contentNotFound(path: path)
  }

  // MARK: - Stamping

  /// `sections` stamped with contextual paths, every track's browse-time artwork (no size
  /// context) resolved at once.
  private func transformSections(
    _ sections: [Section],
    parentPath: String,
    routeEntry: NativeRouteEntry?,
  ) async throws -> [Section] {
    let stamped = try BrowserPathHelper.stampingContextualPaths(sections, parentPath: parentPath)
    let artworkConfig = routeEntry?.artwork
    let artworkLoads = stamped.map { section in
      section.children.map { track in
        Task<ImageSource?, Never> { @MainActor in
          await self.requests.resolveArtworkUrl(track: track, perRouteConfig: artworkConfig)
        }
      }
    }

    var transformed: [Section] = []
    for (section, loads) in zip(stamped, artworkLoads) {
      var children: [Track] = []
      for (track, load) in zip(section.children, loads) {
        let artwork = await load.value
        children.append(artwork.map { track.copying(artworkSource: $0) } ?? track)
      }
      transformed.append(section.copying(children: children))
    }
    return transformed
  }
}
