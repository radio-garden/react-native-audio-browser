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
  func resolveMediaUrl(_ originalUrl: String, track: Track? = nil) async -> MediaResolvedUrl {
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

    // No media-specific config: build the URL from the request-layered base.
    guard let mediaConfig = config.media else {
      let merged = applyMediaResolveLayer(base: baseRequest, resolve: resolveLayer)
      let finalUrl = buildUrl(from: merged)
      logger.debug("No media config; request-layered URL: \(originalUrl) -> \(finalUrl)")
      return MediaResolvedUrl(
        url: finalUrl,
        headers: merged.headers,
        userAgent: merged.userAgent,
      )
    }

    // If there's a transform (async and/or sync), run it as a pipeline: async
    // first, then sync. Each result is copied out of the Nitro bridge immediately
    // (extractConfig) to avoid use-after-free when the Promise is deallocated.
    if mediaConfig.transform != nil || mediaConfig.transformSync != nil {
      do {
        logger.debug("resolveMediaUrl: calling transform callback...")
        var transformedConfig = baseRequest
        if let transform = mediaConfig.transform {
          transformedConfig = try await awaitAsyncConfig(transform(transformedConfig, nil))
        }
        if let transformSync = mediaConfig.transformSync {
          transformedConfig = try await awaitSyncConfig(transformSync(transformedConfig, nil))
        }
        logger.debug("resolveMediaUrl: transform complete")

        let merged = applyMediaResolveLayer(
          base: transformedConfig,
          resolve: resolveLayer,
        )
        let finalUrl = buildUrl(from: merged)

        logger.debug("Media URL transformed: \(originalUrl) -> \(finalUrl)")

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

    // No media transform: apply media static fields over the request-layered base.
    let baseUrl = mediaConfig.baseUrl ?? baseRequest.baseUrl
    if let baseUrl {
      let staticBase = RequestConfig(
        method: baseRequest.method,
        path: originalUrl,
        baseUrl: baseUrl,
        headers: mediaConfig.headers ?? baseRequest.headers,
        query: baseRequest.query,
        body: baseRequest.body,
        contentType: baseRequest.contentType,
        userAgent: mediaConfig.userAgent ?? baseRequest.userAgent,
      )
      let merged = applyMediaResolveLayer(base: staticBase, resolve: resolveLayer)
      let finalUrl = buildUrl(from: merged)
      logger.debug("Media URL with baseUrl: \(originalUrl) -> \(finalUrl)")
      return MediaResolvedUrl(
        url: finalUrl,
        headers: merged.headers,
        userAgent: merged.userAgent,
      )
    }

    return MediaResolvedUrl(url: originalUrl, headers: nil, userAgent: nil)
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
  /// - Returns: ImageSource ready for image loading, or nil if no artwork
  func resolveArtworkUrl(track: Track, perRouteConfig: ArtworkRequestConfig?, imageContext: ImageContext? = nil) async -> ImageSource? {
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
        }
      } else {
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

      // Skip transform at browse-time (no size context) — applied at load-time.
      let hasSize = imageContext?.width != nil || imageContext?.height != nil
      if hasSize {
        if let transform = artworkConfig.transform {
          mergedConfig = try await awaitAsyncConfig(transform(MediaTransformParams(request: mergedConfig, context: imageContext)))
        }
        if let transformSync = artworkConfig.transformSync {
          mergedConfig = try await awaitSyncConfig(transformSync(MediaTransformParams(request: mergedConfig, context: imageContext)))
        }
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
    extractConfig(try await promise.await().await())
  }

  /// Awaits a **sync** config callback (lowers to `Promise<RequestConfig>` — a single
  /// bridge await) and copies the result out. Pairs with `awaitAsyncConfig`.
  func awaitSyncConfig(_ promise: Promise<RequestConfig>) async throws -> RequestConfig {
    extractConfig(try await promise.await())
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
