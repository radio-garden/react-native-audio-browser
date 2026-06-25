import Foundation
import NitroModules

// MARK: - URL Resolution

extension BrowserManager {
  // MARK: - Media URL Resolution

  /// Resolves a media URL using the configured media transform.
  /// Returns the transformed URL, headers, and user-agent for playback.
  ///
  /// Resolution layers, least- to most-specific: shared `request` layer →
  /// `media` transform / static fields → `media.resolve(track)`. The final
  /// `resolve` layer (if configured) is merged last via `mergeRequestConfig`,
  /// so its fields win. The `track` is only used to feed `resolve`; we do NOT
  /// auto-merge `track.request` here.
  ///
  /// `target` is the resolution destination (`.local` for the on-device
  /// AVPlayer, `.cast` for a Cast device that fetches the URL itself). It is
  /// passed as the 3rd argument to the consumer's media `transform` /
  /// `transformSync` callback (after `request`, `routeParams`), so the consumer
  /// can branch on it — emitting a self-contained (query-signed) URL for `.cast`
  /// (request headers do NOT cross to the receiver) vs a header-auth URL for
  /// `.local`. The Cast layer calls this with `.cast`.
  func resolveMediaUrl(_ originalUrl: String, track: Track? = nil, target: MediaResolveTarget = .local) async -> MediaResolvedUrl {
    logger.debug("Resolving media URL: \(originalUrl)")

    // Apply the shared `request` layer first (its transform runs for media too,
    // per the documented contract — e.g. a dynamic baseUrl), then the media
    // transform / static fields on top. The request layer applies even when no
    // `media` config is present, so a relative src still gets baseUrl.
    let baseRequest: RequestConfig
    do {
      try await ensureLayersResolved()
      baseRequest = try await applyLayer(
        resolvedRequestLayer,
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
        merged = try await applyMediaLayer(mediaConfig, to: merged, target: target)
      }
      merged = applyMediaResolveLayer(base: merged, resolve: resolveLayer)
      let finalUrl = buildUrl(from: merged)
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

  /// Media-kind Request-Config Layer application: a transform (async and/or sync)
  /// wins completely — async first, then sync, each result copied out of the Nitro
  /// bridge immediately (extractConfig, via awaitAsync/SyncConfig) — otherwise the
  /// media config's static fields merge over the base with `path` carried from the
  /// base (only a transform may change it). The same rule as `applyLayer`; matches
  /// the web stub and Android.
  private func applyMediaLayer(_ media: MediaRequestConfig, to base: RequestConfig, target: MediaResolveTarget) async throws -> RequestConfig {
    if media.transform != nil || media.transformSync != nil {
      var result = base
      // Pass the resolution destination as the 3rd transform arg so a consumer's
      // media transform can branch on it — emitting a self-contained, query-signed
      // URL for `.cast` (the receiver fetches the media itself; request headers do
      // not cross the boundary) vs a header-auth URL for `.local`.
      if let transform = media.transform {
        result = try await awaitAsyncConfig(transform(result, nil, target))
      }
      if let transformSync = media.transformSync {
        result = try await awaitSyncConfig(transformSync(result, nil, target))
      }
      return result
    }
    let staticMerged = mergeRequestConfig(
      base: base,
      override: RequestConfig(
        method: media.method,
        path: media.path,
        baseUrl: media.baseUrl,
        headers: media.headers,
        query: media.query,
        body: media.body,
        contentType: media.contentType,
        userAgent: media.userAgent,
      ),
    )
    return RequestConfig(
      method: staticMerged.method,
      path: base.path,
      baseUrl: staticMerged.baseUrl,
      headers: staticMerged.headers,
      query: staticMerged.query,
      body: staticMerged.body,
      contentType: staticMerged.contentType,
      userAgent: staticMerged.userAgent,
    )
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
    return MediaResolveComposer.composeResolved(
      async: asyncResolved, sync: syncResolved,
      combine: { self.mergeRequestConfig(base: $0, override: $1) },
    )
  }

  /// Merges the resolve layer (most specific, override-wins) over `base`.
  /// A no-op when `resolve` is nil.
  private func applyMediaResolveLayer(base: RequestConfig, resolve: RequestConfig?) -> RequestConfig {
    guard let resolve else { return base }
    return mergeRequestConfig(base: base, override: resolve)
  }

  // MARK: - Artwork URL Resolution

  /// Resolves an artwork URL for a track using the configured artwork transform.
  /// Returns an ImageSource with transformed URL and headers for image loading.
  ///
  /// - Parameters:
  ///   - track: The track whose artwork URL should be transformed
  ///   - perRouteConfig: Optional per-route artwork config that overrides global config
  ///   - imageContext: Optional size context for CDN URL generation (nil at browse-time)
  ///   - target: Resolution destination (`.local` on-device, `.cast` for a Cast
  ///     device that fetches the artwork itself — so the transform can emit a
  ///     self-contained, query-signed URL since request headers do not cross to
  ///     the receiver). Passed into `MediaTransformParams.target`.
  /// - Returns: ImageSource ready for image loading, or nil if no artwork
  func resolveArtworkUrl(track: Track, perRouteConfig: ArtworkRequestConfig?, imageContext: ImageContext? = nil, target: MediaResolveTarget = .local) async -> ImageSource? {
    if let artwork = track.artwork, SFSymbolRenderer.isSFSymbol(artwork) {
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
      guard let artwork = track.artwork else { return nil }
      return ImageSource(uri: artwork, method: nil, headers: nil, body: nil)
    }

    do {
      // Base config via the shared `request` layer (its transform runs for
      // artwork too), with track.artwork as the default path. Artwork then
      // shapes further via its own resolve / static fields / transform.
      try await ensureLayersResolved()
      var mergedConfig = try await applyLayer(
        resolvedRequestLayer,
        to: RequestConfig(
          method: nil, path: track.artwork, baseUrl: nil, headers: nil,
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
      mergedConfig = mergeRequestConfig(base: mergedConfig, override: artworkStaticConfig)

      if artworkConfig.resolve != nil || artworkConfig.resolveSync != nil {
        var asyncResolved: RequestConfig?
        if let resolve = artworkConfig.resolve { asyncResolved = try await awaitAsyncConfig(resolve(track)) }
        var syncResolved: RequestConfig?
        if let resolveSync = artworkConfig.resolveSync { syncResolved = try await awaitSyncConfig(resolveSync(track)) }
        if let resolved = MediaResolveComposer.composeResolved(
          async: asyncResolved, sync: syncResolved,
          combine: { self.mergeRequestConfig(base: $0, override: $1) },
        ) {
          mergedConfig = mergeRequestConfig(base: mergedConfig, override: resolved)
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
          var existingQuery = mergedConfig.query ?? [:]
          for (key, value) in contextQuery {
            existingQuery[key] = value
          }
          mergedConfig = RequestConfig(
            method: mergedConfig.method,
            path: mergedConfig.path,
            baseUrl: mergedConfig.baseUrl,
            headers: mergedConfig.headers,
            query: existingQuery,
            body: mergedConfig.body,
            contentType: mergedConfig.contentType,
            userAgent: mergedConfig.userAgent,
          )
        }
      }

      // Transform (async first, then sync), receiving the image context — which is
      // nil at browse time, matching the web stub and Android. (Previously skipped
      // without a size, leaving browse-time `artworkSource` untransformed for JS
      // consumers; load-time surfaces re-resolve Track-first with the real size,
      // so running it here cannot double-transform.)
      if let transform = artworkConfig.transform {
        mergedConfig = try await awaitAsyncConfig(transform(MediaTransformParams(request: mergedConfig, context: imageContext, target: target)))
      }
      if let transformSync = artworkConfig.transformSync {
        mergedConfig = try await awaitSyncConfig(transformSync(MediaTransformParams(request: mergedConfig, context: imageContext, target: target)))
      }

      // Substitute the `{id}` template token with the track's id across path/query/header values.
      // (Configs without the token are unaffected — e.g. browse artwork.) Only when the track has an id.
      if let id = track.id, !id.isEmpty {
        mergedConfig = substituteTrackId(in: mergedConfig, id: id)
      }

      // Build final URL - if no path after merging, no artwork to transform
      guard mergedConfig.path != nil else {
        return nil
      }

      let uri = buildUrl(from: mergedConfig)

      // Build headers map, merging explicit headers with userAgent
      var headers = mergedConfig.headers ?? [:]
      if let userAgent = mergedConfig.userAgent, headers["User-Agent"] == nil {
        headers["User-Agent"] = userAgent
      }

      logger.debug("Artwork URL transformed: \(track.artwork ?? "nil") -> \(uri)")

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

  // MARK: - URL Building

  private func buildUrl(from config: RequestConfig) -> String {
    let path = config.path ?? ""
    let baseUrl = config.baseUrl

    var url = BrowserPathHelper.buildUrl(baseUrl: baseUrl, path: path)

    // Add query parameters if any
    if let query = config.query, !query.isEmpty {
      url = BrowserPathHelper.appendQuery(query, to: url)
    }

    return url
  }

  // MARK: - Config Utilities

  /// Replaces the `{id}` token with the track id in a request config's path, query values, and
  /// header values. Used so a `nowPlayingArtwork` like `{ path: "/artwork/{id}" }` resolves.
  private func substituteTrackId(in config: RequestConfig, id: String) -> RequestConfig {
    func sub(_ s: String?) -> String? { s?.replacingOccurrences(of: "{id}", with: id) }
    func subDict(_ d: [String: String]?) -> [String: String]? {
      guard let d else { return nil }
      return d.mapValues { $0.replacingOccurrences(of: "{id}", with: id) }
    }
    return RequestConfig(
      method: config.method,
      path: sub(config.path),
      baseUrl: config.baseUrl,
      headers: subDict(config.headers),
      query: subDict(config.query),
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
  /// Internal so `applyLayer` (in BrowserManager.swift) can copy transform results.
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

  /// Merges two RequestConfig objects, with override values taking precedence.
  private func mergeRequestConfig(base: RequestConfig, override: RequestConfig) -> RequestConfig {
    RequestConfig(
      method: override.method ?? base.method,
      path: override.path ?? base.path,
      baseUrl: override.baseUrl ?? base.baseUrl,
      headers: mergeDicts(base.headers, override.headers),
      query: mergeDicts(base.query, override.query),
      body: override.body ?? base.body,
      contentType: override.contentType ?? base.contentType,
      userAgent: override.userAgent ?? base.userAgent,
    )
  }
}
