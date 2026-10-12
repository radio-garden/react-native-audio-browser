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
  /// static layer when it has none. Both resolvers run at once, and a config change or
  /// `clearContentCache` while they run resolves them again.
  func resolvedLayers() async throws -> ResolvedLayers {
    try await layers.value { [weak self] in
      guard let self else { throw CancellationError() }
      // Read on every run: a retry after a config change must resolve the new config's layers.
      let config = self.config
      // The resolver is Promise-only (the TS layer wraps a sync-or-async thunk in
      // Promise.resolve), so its result is the JS promise behind the bridge promise.
      let request = config.requestResolver?()
      let browse = config.browseResolver?()
      return try await ResolvedLayers(
        request: request?.await().await() ?? config.request,
        browse: browse?.await().await() ?? config.browse,
      )
    }
  }

  /// Applies one Request-Config Layer (request / browse / route) onto `base`: its transforms
  /// (async, then sync) when it has any, else its static fields (`RequestLayer.apply`).
  func applyLayer(
    _ layer: TransformableRequestConfig?,
    to base: RequestConfig,
    params: [String: String],
  ) async throws -> RequestConfig {
    guard let layer else { return base }
    var transforms: [nonisolated(nonsending)(RequestConfig) async throws -> RequestConfig] = []
    if let transform = layer.transform {
      transforms.append { try await self.awaitAsyncConfig(transform($0, params)) }
    }
    if let transformSync = layer.transformSync {
      transforms.append { try await self.awaitSyncConfig(transformSync($0, params)) }
    }
    let fields = RequestConfig(
      method: layer.method, path: nil, baseUrl: layer.baseUrl, headers: layer.headers,
      query: layer.query, body: layer.body, contentType: layer.contentType, userAgent: layer.userAgent,
    )
    return try await RequestLayer.apply(fields, transforms: transforms, to: base)
  }

  /// Builds the HTTP request for an API-backed route by layering
  /// request → kind → route configs. `initialQuery` seeds request-level query
  /// params (e.g. search q/mode/…) onto the base before the route layer, so they
  /// survive into `request.query` even when a layer's transform "wins
  /// completely" (it is handed only the base, so the route config's own static
  /// query would otherwise be dropped). `routeConfig` is nil for the implicit
  /// browse default; `kind` names the per-kind layer, nil for kinds with none. The request and
  /// kind layers come from one resolution, so a config change mid-request can't mix them.
  func buildApiRequest(
    kind: KeyPath<ResolvedLayers, TransformableRequestConfig?>?,
    _ routeConfig: TransformableRequestConfig?,
    path: String,
    params: [String: String],
    initialQuery: [String: String]? = nil,
  ) async throws -> HttpClient.HttpRequest {
    var merged = RequestConfig(
      method: nil, path: path, baseUrl: nil, headers: nil,
      query: nil, body: nil, contentType: nil, userAgent: nil,
    )
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
      method: merged.method?.stringValue ?? "GET",
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

  /// Resolves a media URL using the configured media transform.
  /// Returns the transformed URL, headers, and user-agent for playback.
  ///
  /// Resolution layers, least- to most-specific: shared `request` layer →
  /// `media` transform / static fields → `media.resolve(track)`. The final
  /// `resolve` layer (if configured) is merged last,
  /// so its fields win. The `track` is only used to feed `resolve`; we do NOT
  /// auto-merge `track.request` here.
  func resolveMediaUrl(_ originalUrl: String, track: Track? = nil) async -> MediaResolvedUrl {
    logger.debug("Resolving media URL: \(originalUrl)")

    // Apply the shared `request` layer first (its transform runs for media too,
    // per the documented contract — e.g. a dynamic baseUrl), then the media
    // transform / static fields on top. The request layer applies even when no
    // `media` config is present, so a relative src still gets baseUrl.
    let baseRequest: RequestConfig
    do {
      baseRequest = try await applyLayer(
        resolvedLayers().request,
        to: RequestConfig(
          method: nil, path: originalUrl, baseUrl: nil, headers: nil,
          query: nil, body: nil, contentType: nil, userAgent: nil,
        ),
        params: [:],
      )
    } catch {
      logger.error("Media request layer failed: \(error.localizedDescription)")
      return MediaResolvedUrl(url: originalUrl, headers: nil, userAgent: nil)
    }

    // Resolve the consumer-supplied final media layer once, up front. This is the
    // most-specific layer and is merged over every branch's result below.
    let resolveLayer: RequestConfig?
    do {
      resolveLayer = try await resolveMediaTrackConfig(track)
    } catch {
      logger.error("Media resolve callback failed: \(error.localizedDescription)")
      resolveLayer = nil
    }

    // request → media (kind layer) → media.resolve(track), resolve winning.
    // Matches the web stub and Android. (The old static branch dropped a media
    // config's static query/method/body/contentType, and dropped its headers
    // entirely when no baseUrl was configured anywhere.)
    do {
      var merged = baseRequest
      if let mediaConfig = config.media {
        merged = try await applyMediaLayer(mediaConfig, to: merged)
      }
      if let resolveLayer { merged = RequestLayer.merge(merged, resolveLayer) }
      let finalUrl = RequestLayer.buildUrl(merged)
      logger.debug("Media URL resolved: \(originalUrl) -> \(finalUrl)")
      return MediaResolvedUrl(
        url: finalUrl,
        headers: merged.headers,
        userAgent: merged.userAgent,
      )
    } catch {
      logger.error("Media transform failed: \(error.localizedDescription)")
      return MediaResolvedUrl(url: originalUrl, headers: nil, userAgent: nil)
    }
  }

  /// The media kind's Request-Config Layer over `base`: its transforms, when it has any, else
  /// its static fields (`RequestLayer.apply`).
  private func applyMediaLayer(_ media: MediaRequestConfig, to base: RequestConfig) async throws -> RequestConfig {
    var transforms: [nonisolated(nonsending)(RequestConfig) async throws -> RequestConfig] = []
    if let transform = media.transform {
      transforms.append { try await self.awaitAsyncConfig(transform($0, nil)) }
    }
    if let transformSync = media.transformSync {
      transforms.append { try await self.awaitSyncConfig(transformSync($0, nil)) }
    }
    let fields = RequestConfig(
      method: media.method, path: nil, baseUrl: media.baseUrl, headers: media.headers,
      query: media.query, body: media.body, contentType: media.contentType, userAgent: media.userAgent,
    )
    return try await RequestLayer.apply(fields, transforms: transforms, to: base)
  }

  /// Invokes the consumer-supplied `media.resolve`/`resolveSync(track)` and merges
  /// the results (async first, then sync, sync winning) into a concrete
  /// `RequestConfig`. Returns `nil` when neither is configured or no track is
  /// available. Each result is copied out of the Nitro bridge immediately.
  func resolveMediaTrackConfig(_ track: Track?) async throws -> RequestConfig? {
    guard let track, let media = config.media else { return nil }
    var asyncResolved: RequestConfig?
    if let resolve = media.resolve { asyncResolved = try await awaitAsyncConfig(resolve(track)) }
    var syncResolved: RequestConfig?
    if let resolveSync = media.resolveSync { syncResolved = try await awaitSyncConfig(resolveSync(track)) }
    return RequestLayer.mergeResolved(async: asyncResolved, sync: syncResolved)
  }

  // MARK: - Artwork URL Resolution

  /// Resolves an artwork URL for a track using the configured artwork transform.
  /// Returns an ImageSource with transformed URL and headers for image loading.
  ///
  /// - Parameters:
  ///   - track: The track whose artwork URL should be transformed
  ///   - perRouteConfig: Optional per-route artwork config that overrides global config
  ///   - imageContext: Optional size context for CDN URL generation (nil at browse-time)
  /// - Returns: ImageSource ready for image loading, or nil if no artwork
  func resolveArtworkUrl(track: Track, perRouteConfig: ArtworkRequestConfig?, imageContext: ImageContext? = nil) async -> ImageSource? {
    if let artwork = track.artwork?.url, SFSymbolRenderer.isSFSymbol(artwork) {
      let canvasSize: CGSize = if let w = imageContext?.width, let h = imageContext?.height {
        CGSize(width: w, height: h)
      } else {
        SFSymbolRenderer.defaultCanvasSize
      }
      if let uri = SFSymbolRenderer.shared.render(artwork, canvasSize: canvasSize) {
        return ImageSource(uri: uri, method: nil, headers: nil, body: nil)
      }
      return nil
    }

    // Determine effective artwork config: per-route overrides global
    let effectiveArtworkConfig = perRouteConfig ?? config.artwork

    // If no artwork config and no track.artwork, nothing to transform
    if effectiveArtworkConfig == nil, track.artwork == nil {
      return nil
    }

    // If no artwork config, return original artwork URL as simple ImageSource
    guard let artworkConfig = effectiveArtworkConfig else {
      guard let artwork = track.artwork?.url else { return nil }
      return ImageSource(uri: artwork, method: nil, headers: nil, body: nil)
    }

    do {
      // Base config via the shared `request` layer (its transform runs for
      // artwork too), with track.artwork as the default path. Artwork then
      // shapes further via its own resolve / static fields / transform.
      var mergedConfig = try await applyLayer(
        resolvedLayers().request,
        to: RequestConfig(
          method: nil, path: track.artwork?.url, baseUrl: nil, headers: nil,
          query: nil, body: nil, contentType: nil, userAgent: nil,
        ),
        params: [:],
      )

      // Artwork config's static fields always apply (not resolve/transform — those
      // run separately), with the per-track resolve merged over them — resolve
      // wins. Matches the web stub and Android (the old either/or skipped static
      // fields whenever a resolver was configured).
      let artworkStaticConfig = RequestConfig(
        method: artworkConfig.method,
        path: artworkConfig.path,
        baseUrl: artworkConfig.baseUrl,
        headers: artworkConfig.headers,
        query: artworkConfig.query,
        body: artworkConfig.body,
        contentType: artworkConfig.contentType,
        userAgent: artworkConfig.userAgent,
      )
      mergedConfig = RequestLayer.merge(mergedConfig, artworkStaticConfig)

      if artworkConfig.resolve != nil || artworkConfig.resolveSync != nil {
        var asyncResolved: RequestConfig?
        if let resolve = artworkConfig.resolve { asyncResolved = try await awaitAsyncConfig(resolve(track)) }
        var syncResolved: RequestConfig?
        if let resolveSync = artworkConfig.resolveSync { syncResolved = try await awaitSyncConfig(resolveSync(track)) }
        if let resolved = RequestLayer.mergeResolved(async: asyncResolved, sync: syncResolved) {
          mergedConfig = RequestLayer.merge(mergedConfig, resolved)
        } else if track.artwork == nil {
          // A resolver ran but produced nothing, and there's no artwork URL either
          // → no artwork (matches the web stub).
          return nil
        }
      }

      // Apply image query params if configured and imageContext is provided
      let queryParams = artworkConfig.imageQueryParams
      if let queryParams, let imageContext {
        var contextQuery: [String: String] = [:]
        if let widthKey = queryParams.width, let width = imageContext.width {
          contextQuery[widthKey] = String(Int(width))
        }
        if let heightKey = queryParams.height, let height = imageContext.height {
          contextQuery[heightKey] = String(Int(height))
        }

        if !contextQuery.isEmpty {
          logger.debug("Adding image query params: \(contextQuery)")
          mergedConfig = mergedConfig.copying(query: RequestLayer.mergeDicts(mergedConfig.query, contextQuery))
        }
      }

      // Transform (async first, then sync), receiving the image context — which is
      // nil at browse time, matching the web stub and Android. (Previously skipped
      // without a size, leaving browse-time `artworkSource` untransformed for JS
      // consumers; load-time surfaces re-resolve Track-first with the real size,
      // so running it here cannot double-transform.)
      if let transform = artworkConfig.transform {
        mergedConfig = try await awaitAsyncConfig(transform(MediaTransformParams(request: mergedConfig, context: imageContext)))
      }
      if let transformSync = artworkConfig.transformSync {
        mergedConfig = try await awaitSyncConfig(transformSync(MediaTransformParams(request: mergedConfig, context: imageContext)))
      }

      // Substitute the `{id}` template token with the track's id across path/query/header values.
      // (Configs without the token are unaffected — e.g. browse artwork.) A track without a
      // non-blank id leaves the token LITERALLY in place — the request then visibly 404s, which
      // is intended (self-describing error trail) — with a single warning logged below once the
      // URL is built.
      mergedConfig = substituteTrackId(in: mergedConfig, id: track.id)
      let hasUnfilledIdToken = TrackIdTemplate.containsToken(mergedConfig.path)
        || TrackIdTemplate.containsToken(mergedConfig.query)
        || TrackIdTemplate.containsToken(mergedConfig.headers)

      // Build final URL - if no path after merging, no artwork to transform
      guard mergedConfig.path != nil else {
        return nil
      }

      let uri = RequestLayer.buildUrl(mergedConfig)

      // Build headers map, merging explicit headers with userAgent
      var headers = mergedConfig.headers ?? [:]
      if let userAgent = mergedConfig.userAgent, headers["User-Agent"] == nil {
        headers["User-Agent"] = userAgent
      }

      if hasUnfilledIdToken {
        logger.warning("nowPlayingArtwork: track has no id — '{id}' left unfilled in \(uri)")
      }

      logger.debug("Artwork URL transformed: \(track.artwork?.url ?? "nil") -> \(uri)")

      return ImageSource(
        uri: uri,
        method: mergedConfig.method,
        headers: headers.isEmpty ? nil : headers,
        body: mergedConfig.body,
      )
    } catch {
      logger.error("Failed to transform artwork URL for track: \(track.title), error: \(error.localizedDescription)")
      // On error, return nil to avoid broken images
      return nil
    }
  }

  // MARK: - Config Utilities

  /// Replaces the `{id}` token with the track id in a request config's path, query values, and
  /// header values. Used so a `nowPlayingArtwork` like `{ path: "/artwork/{id}" }` resolves.
  /// A nil/blank id leaves the values unchanged (token left literal — see TrackIdTemplate).
  private func substituteTrackId(in config: RequestConfig, id: String?) -> RequestConfig {
    RequestConfig(
      method: config.method,
      path: TrackIdTemplate.substitute(config.path, id: id),
      baseUrl: config.baseUrl,
      headers: TrackIdTemplate.substitute(config.headers, id: id),
      query: TrackIdTemplate.substitute(config.query, id: id),
      body: config.body,
      contentType: config.contentType,
      userAgent: config.userAgent,
    )
  }

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

  /// Extracts all values from a RequestConfig into a new instance to avoid
  /// memory corruption in Nitro's Swift-C++ bridge when the Promise is deallocated.
  func extractConfig(_ config: RequestConfig) -> RequestConfig {
    RequestConfig(
      method: config.method,
      path: config.path,
      baseUrl: config.baseUrl,
      headers: config.headers,
      query: config.query,
      body: config.body,
      contentType: config.contentType,
      userAgent: config.userAgent,
    )
  }
}

extension RequestConfig: RequestFields {}

extension RequestPipeline: MediaResolving {
  /// Runs the `nowPlayingArtwork` config for every playing track, id or not: an id-less track
  /// leaves an `{id}` token unfilled, so the request visibly fails rather than being skipped.
  func resolveNowPlayingArtwork(track: Track, imageContext: ImageContext?) async -> ImageSource? {
    await resolveArtworkUrl(track: track, perRouteConfig: config.nowPlayingArtwork, imageContext: imageContext)
  }
}
