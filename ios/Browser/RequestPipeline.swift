import Foundation
import NitroModules
import os.log

/// Builds and runs the browser's HTTP requests: the Request-Config Layers for browse, search,
/// media and artwork, and the API fetches.
@MainActor
final class RequestPipeline {
  let logger = Logger(subsystem: "com.audiobrowser", category: "RequestPipeline")
  private let httpClient = HttpClient()
  /// The request/browse thunks resolve once per content generation.
  private let layers = GenerationMemo<ResolvedLayers>()

  /// Set by `BrowserManager`; a new config resolves the layers again.
  var config = BrowserConfig() {
    didSet { layers.invalidate() }
  }

  nonisolated init() {}

  /// Resolves the request and browse layers again on next use (e.g. after a locale change).
  func invalidateLayers() {
    layers.invalidate()
  }

  struct ResolvedLayers {
    let request: TransformableRequestConfig?
    let browse: TransformableRequestConfig?
  }

  /// The request and browse layers of the current config: each resolver's result, or the
  /// static layer when it has none. The resolvers run one after the other, request first, as on
  /// Android and web, and a config change or `clearContentCache` while they run resolves them
  /// again.
  func resolvedLayers() async throws -> ResolvedLayers {
    try await layers.value { [weak self] in
      guard let self else { throw CancellationError() }
      // Read on every run: a retry after a config change must resolve the new config's layers.
      let config = self.config
      // The resolver is Promise-only (the TS layer wraps a sync-or-async thunk in
      // Promise.resolve), so its result is the JS promise behind the bridge promise.
      let request = try await config.requestResolver?().await().await() ?? config.request
      let browse = try await config.browseResolver?().await().await() ?? config.browse
      return ResolvedLayers(request: request, browse: browse)
    }
  }

  /// Applies one Request-Config Layer (request / browse / media / route) onto `base`: its
  /// transforms (async, then sync) when it has any, else its static fields (`RequestLayer.apply`).
  func applyLayer(
    _ layer: (some TransformableLayer)?,
    to base: RequestConfig,
    params: [String: String]?,
  ) async throws -> RequestConfig {
    guard let layer else { return base }
    var transforms: [nonisolated(nonsending)(RequestConfig) async throws -> RequestConfig] = []
    if let transform = layer.transform {
      transforms.append { try await self.awaitAsyncConfig(transform($0, params)) }
    }
    if let transformSync = layer.transformSync {
      transforms.append { try await self.awaitSyncConfig(transformSync($0, params)) }
    }
    return try await RequestLayer.apply(RequestConfig(fieldsOf: layer), transforms: transforms, to: base)
  }

  /// Builds the HTTP request for an API-backed route by layering
  /// request → kind → route configs. `initialQuery` seeds request-level query
  /// params (e.g. search q/mode/…) onto the base before the route layer, so they
  /// survive into `request.query` even when a layer's transform "wins
  /// completely" (it is handed only the base, so the route config's own static
  /// query would otherwise be dropped). `routeConfig` is nil for the implicit
  /// browse default; `kind` names the per-kind layer, nil for kinds with none. The request and
  /// kind layers come from one resolution; `routeConfig` from the caller's config.
  func buildApiRequest(
    kind: KeyPath<ResolvedLayers, TransformableRequestConfig?>?,
    _ routeConfig: TransformableRequestConfig?,
    path: String,
    params: [String: String],
    initialQuery: [String: String]? = nil,
  ) async throws -> HttpClient.HttpRequest {
    var merged = RequestConfig.base(path: path)
    let layers = try await resolvedLayers()
    merged = try await applyLayer(layers.request, to: merged, params: params)
    if let initialQuery, !initialQuery.isEmpty {
      merged = merged.copying(query: RequestLayer.mergeDicts(merged.query, initialQuery))
    }
    merged = try await applyLayer(kind.flatMap { layers[keyPath: $0] }, to: merged, params: params)
    merged = try await applyLayer(routeConfig, to: merged, params: params)

    guard merged.baseUrl != nil else {
      throw BrowserError.invalidConfiguration("No URL configured for route")
    }
    return HttpClient.HttpRequest(
      url: RequestLayer.buildUrl(merged, defaultPath: path),
      method: merged.method ?? .get,
      headers: merged.headers,
      body: merged.body,
      contentType: merged.contentType ?? HttpClient.defaultContentType,
      userAgent: merged.userAgent ?? HttpClient.defaultUserAgent,
    )
  }

  /// Fetches a page object (`{title,path,children:[…]}`) through the request → browse → route
  /// layers.
  func fetchPage(
    _ routeConfig: TransformableRequestConfig?,
    path: String,
    params: [String: String],
  ) async throws -> ResolvedTrack {
    let request = try await buildApiRequest(
      kind: \.browse, routeConfig, path: path, params: params,
    )

    logger.debug("Resolving content from API")
    logger.debug("  path: \(path)")
    logger.debug("  url: \(request.url)")

    let result: JsonResolvedTrack = try await httpClient.requestJson(request, as: JsonResolvedTrack.self)
    let nitroResult = result.toNitro()

    logger.debug("Resolved: \(nitroResult.title)")
    if let sections = nitroResult.normalizedSections {
      logger.debug("  children: \(sections.reduce(0) { $0 + $1.children.count }) tracks")
    }

    return nitroResult
  }

  /// Fetches a search route's bare Track array through the request → search route layers. The
  /// search params go through `initialQuery`, so they reach the URL even when a transform wins;
  /// the config's static path seeds the base, since a layer's static path never applies.
  func fetchSearch(_ searchConfig: TransformableRequestConfig, params: SearchParams) async throws -> [Track] {
    let request = try await buildApiRequest(
      kind: nil, searchConfig, path: searchConfig.path ?? "", params: ["q": params.query],
      initialQuery: PageResolver.searchQueryParams(params),
    )
    logger.debug("Searching via API: \(request.url)")
    let jsonTracks: [JsonTrack] = try await httpClient.requestJson(request, as: [JsonTrack].self)
    return jsonTracks.map { $0.toNitro() }
  }

  // MARK: - Media URL Resolution

  /// The media request for `originalUrl`: the shared `request` layer, then the `media` layer,
  /// then `media.resolve(track)` merged over them. `track` only feeds `resolve`; its own
  /// `request` is not merged here. A failing layer leaves the URL as it is.
  func resolveMediaUrl(_ originalUrl: String, track: Track? = nil) async -> MediaResolvedUrl {
    let base: RequestConfig
    do {
      base = try await applyLayer(resolvedLayers().request, to: .base(path: originalUrl), params: [:])
    } catch {
      logger.error("Media request layer failed: \(error.localizedDescription)")
      return MediaResolvedUrl(url: originalUrl, headers: nil, userAgent: nil)
    }

    let resolveLayer: RequestConfig?
    do {
      resolveLayer = try await resolveMediaTrackConfig(track)
    } catch {
      logger.error("Media resolve callback failed: \(error.localizedDescription)")
      resolveLayer = nil
    }

    do {
      var merged = try await applyLayer(config.media, to: base, params: nil)
      if let resolveLayer { merged = RequestLayer.merge(merged, resolveLayer) }
      let finalUrl = RequestLayer.buildUrl(merged)
      logger.debug("Media URL resolved: \(originalUrl) -> \(finalUrl)")
      return MediaResolvedUrl(url: finalUrl, headers: merged.headers, userAgent: merged.userAgent)
    } catch {
      logger.error("Media transform failed: \(error.localizedDescription)")
      return MediaResolvedUrl(url: originalUrl, headers: nil, userAgent: nil)
    }
  }

  /// The config the `media.resolve` / `resolveSync(track)` callbacks produce, nil when neither is
  /// configured or there is no track.
  func resolveMediaTrackConfig(_ track: Track?) async throws -> RequestConfig? {
    guard let track, let media = config.media else { return nil }
    return try await resolved(media.resolve, media.resolveSync, for: track)
  }

  /// What a `resolve` (async) and `resolveSync` produce for `track`, the sync one winning.
  private func resolved(
    _ resolve: ((Track) -> Promise<Promise<RequestConfig>>)?,
    _ resolveSync: ((Track) -> Promise<RequestConfig>)?,
    for track: Track,
  ) async throws -> RequestConfig? {
    var asyncResolved: RequestConfig?
    if let resolve { asyncResolved = try await awaitAsyncConfig(resolve(track)) }
    var syncResolved: RequestConfig?
    if let resolveSync { syncResolved = try await awaitSyncConfig(resolveSync(track)) }
    return RequestLayer.mergeResolved(async: asyncResolved, sync: syncResolved)
  }

  // MARK: - Artwork URL Resolution

  /// The image source for `track`'s artwork: an SF Symbol rendered to a file, the artwork URL
  /// as is without an artwork config, else the shared `request` layer and the artwork config in
  /// `RequestLayer.artwork`'s order. `perRouteConfig` replaces the global artwork config;
  /// `imageContext` is the display size, nil at browse time. Nil when there is no artwork or a
  /// callback fails.
  func resolveArtworkUrl(track: Track, perRouteConfig: ArtworkRequestConfig?, imageContext: ImageContext? = nil) async -> ImageSource? {
    let artworkUrl = track.artwork?.url
    if let artworkUrl, SFSymbolRenderer.isSFSymbol(artworkUrl) {
      let canvasSize: CGSize = if let w = imageContext?.width, let h = imageContext?.height {
        CGSize(width: w, height: h)
      } else {
        SFSymbolRenderer.defaultCanvasSize
      }
      return SFSymbolRenderer.shared.render(artworkUrl, canvasSize: canvasSize)
        .map { ImageSource(uri: $0, method: nil, headers: nil, body: nil) }
    }

    guard let artworkConfig = perRouteConfig ?? config.artwork else {
      return artworkUrl.map { ImageSource(uri: $0, method: nil, headers: nil, body: nil) }
    }

    do {
      let base = try await applyLayer(resolvedLayers().request, to: .base(path: artworkUrl), params: [:])
      let resolve: (nonisolated(nonsending)() async throws -> RequestConfig?)? =
        if artworkConfig.resolve != nil || artworkConfig.resolveSync != nil {
          { try await self.resolved(artworkConfig.resolve, artworkConfig.resolveSync, for: track) }
        } else {
          nil
        }
      var transforms: [nonisolated(nonsending)(RequestConfig) async throws -> RequestConfig] = []
      if let transform = artworkConfig.transform {
        transforms.append { try await self.awaitAsyncConfig(transform(MediaTransformParams(request: $0, context: imageContext))) }
      }
      if let transformSync = artworkConfig.transformSync {
        transforms.append { try await self.awaitSyncConfig(transformSync(MediaTransformParams(request: $0, context: imageContext))) }
      }
      guard let request = try await RequestLayer.artwork(
        RequestConfig(fieldsOf: artworkConfig),
        over: base,
        resolve: resolve,
        trackHasArtwork: track.artwork != nil,
        sizeQuery: RequestLayer.sizeQuery(artworkConfig.imageQueryParams, imageContext),
        transforms: transforms,
        id: track.id,
      ) else { return nil }

      let uri = RequestLayer.buildUrl(request)
      // An unfilled `{id}` stays in the URL, so the request fails visibly.
      if RequestLayer.hasUnfilledIdToken(request) {
        logger.warning("nowPlayingArtwork: track has no id — '{id}' left unfilled in \(uri)")
      }
      logger.debug("Artwork URL transformed: \(artworkUrl ?? "nil") -> \(uri)")
      return ImageSource(
        uri: uri,
        method: request.method,
        headers: RequestLayer.imageHeaders(request),
        body: request.body,
      )
    } catch {
      logger.error("Failed to transform artwork URL for track: \(track.title), error: \(error.localizedDescription)")
      return nil
    }
  }

  // MARK: - Config Utilities

  /// Awaits an **async** config callback and copies the result out of the Nitro
  /// bridge. The callback lowers to `Promise<Promise<RequestConfig>>` (bridge hop →
  /// JS promise), so it is a DOUBLE await. This await depth is the bug-prone part
  /// (single-awaiting an async callback hands a `Promise` downstream — the original
  /// "empty config" bug), so it lives in exactly one place. Pairs with `awaitSyncConfig`.
  func awaitAsyncConfig(_ promise: Promise<Promise<RequestConfig>>) async throws -> RequestConfig {
    try await extractConfig(promise.await().await())
  }

  /// Awaits a **sync** config callback (lowers to `Promise<RequestConfig>` — a single
  /// bridge await) and copies the result out. Pairs with `awaitAsyncConfig`.
  func awaitSyncConfig(_ promise: Promise<RequestConfig>) async throws -> RequestConfig {
    try await extractConfig(promise.await())
  }

  /// A copy of `config`, so nothing reads the Nitro bridge's value after its Promise is gone.
  func extractConfig(_ config: RequestConfig) -> RequestConfig {
    RequestConfig(fieldsOf: config)
  }
}

extension RequestConfig: RequestFields {}
extension ArtworkRequestConfig: RequestFieldValues {}

/// A Request-Config Layer with transforms: the request, browse, media and route configs.
protocol TransformableLayer: RequestFieldValues where Method == HttpMethod {
  var transform: ((RequestConfig, [String: String]?) -> Promise<Promise<RequestConfig>>)? { get }
  var transformSync: ((RequestConfig, [String: String]?) -> Promise<RequestConfig>)? { get }
}

extension TransformableRequestConfig: TransformableLayer {}
extension MediaRequestConfig: TransformableLayer {}

extension RequestPipeline: MediaResolving {
  /// Runs the `nowPlayingArtwork` config for every playing track, id or not: an id-less track
  /// leaves an `{id}` token unfilled, so the request visibly fails rather than being skipped.
  func resolveNowPlayingArtwork(track: Track, imageContext: ImageContext?) async -> ImageSource? {
    await resolveArtworkUrl(track: track, perRouteConfig: config.nowPlayingArtwork, imageContext: imageContext)
  }
}
