import Foundation
import NitroModules
import os.log

/// Turns a path into its page: matches the route, resolves its source, then stamps contextual
/// paths and browse-time artwork. Also resolves the tabs and search routes.
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

  /// The page at `path`, uncached and without favorites hydrated. A path no route matches is
  /// fetched through the request and browse layers. The result carries `sections`, never
  /// `children` (ADR 0010).
  func resolve(_ path: String) async throws -> ResolvedTrack {
    let routeEntry = config.routes.flatMap { SimpleRouter.browseRoute(for: path, in: $0) }

    let resolvedTrack: ResolvedTrack = if let (entry, routeMatch) = routeEntry {
      try await resolveRouteEntry(entry, path: path, params: routeMatch.params)
    } else {
      try await requests.fetchPage(
        nil, path: path, params: ["path": path],
      )
    }

    if let sections = resolvedTrack.normalizedSections {
      // Before the sections transform, while each level is still attributable (ADR 0011).
      InertStyleDiagnostic.warn(path: path, pageStyle: resolvedTrack.style, sections: sections)
      let transformed = try await transformSections(
        sections, parentPath: path, routeEntry: routeEntry?.0,
      )
      return resolvedTrack.copying(sections: transformed, children: .some(nil))
    }

    return resolvedTrack
  }

  /// The `__tabs__` route's page, flattened. Nil when there is no such route.
  func tabs() async throws -> [Track]? {
    guard let tabsEntry = config.routes?.first(where: { $0.path == SimpleRouter.tabsRoutePath }) else { return nil }
    let resolved = try await resolveRouteEntry(tabsEntry, path: SimpleRouter.tabsRoutePath, params: [:])
    return resolved.flattenedChildren ?? []
  }

  /// The `__search__` route's results for `params`, unhydrated. Throws `contentNotFound` when
  /// that route is missing or has neither a callback nor a config.
  func search(_ params: SearchParams) async throws -> [Track] {
    guard let searchEntry = config.routes?.first(where: { $0.path == SimpleRouter.searchRoutePath }) else {
      throw BrowserError.contentNotFound(path: SimpleRouter.searchRoutePath)
    }
    if let callback = searchEntry.searchCallback {
      return try await callback(params).awaitNested()
    }
    if let searchConfig = searchEntry.searchConfig {
      return try await requests.fetchSearch(searchConfig, params: params)
    }
    throw BrowserError.contentNotFound(path: SimpleRouter.searchRoutePath)
  }

  // MARK: - Route Resolution

  private func resolveRouteEntry(
    _ entry: NativeRouteEntry,
    path: String,
    params: [String: String],
  ) async throws -> ResolvedTrack {
    if let callback = entry.browseCallback {
      let callbackParam = BrowserSourceCallbackParam(path: path, routeParams: params)
      // Nitro flattens `(ResolvedTrack | BrowseError) | Promise<BrowseResult>` into three arms:
      // a sync track, a sync error, or a promise of the same two.
      switch try await callback(callbackParam).awaitCancellable() {
      case let .first(resolvedTrack):
        return resolvedTrack
      case let .second(browseError):
        throw BrowserError.callbackError(browseError.error)
      case let .third(promise):
        switch try await promise.awaitCancellable() {
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

  /// `sections` with contextual paths stamped and every track's browse-time artwork (no size
  /// context) resolved, all tracks at once.
  private func transformSections(
    _ sections: [Section],
    parentPath: String,
    routeEntry: NativeRouteEntry?,
  ) async throws -> [Section] {
    let stamped = try BrowserPathHelper.stampingContextualPaths(sections, parentPath: parentPath)
    let artworkConfig = routeEntry?.artwork
    let tracks = stamped.flatMap(\.children)
    let artwork = await Array(tracks.indices).concurrentMap { index in
      await self.requests.resolveArtworkUrl(track: tracks[index], perRouteConfig: artworkConfig)
    }
    // A cancelled resolve gives no artwork; that page must not reach the cache.
    try Task.checkCancellation()
    var next = 0
    return stamped.map { section in
      section.copying(children: section.children.map { track in
        defer { next += 1 }
        return artwork[next].map { track.copying(artworkSource: $0) } ?? track
      })
    }
  }
}
