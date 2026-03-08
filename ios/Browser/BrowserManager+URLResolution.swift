import Foundation
import NitroModules

// MARK: - URL Resolution

extension BrowserManager {
  // MARK: - Media URL Resolution

  /// Resolves a media URL using the configured media transform.
  /// Returns the transformed URL, headers, and user-agent for playback.
  func resolveMediaUrl(_ originalUrl: String) async -> MediaResolvedUrl {
    guard let mediaConfig = config.media else {
      logger.debug("No media config, using original URL: \(originalUrl)")
      return MediaResolvedUrl(url: originalUrl, headers: nil, userAgent: nil)
    }

    logger.debug("Resolving media URL: \(originalUrl)")

    // If there's a transform function, call it
    if let transform = mediaConfig.transform {
      do {
        // Create base request config with original URL as path
        let baseRequest = RequestConfig(
          method: config.request?.method,
          path: originalUrl,
          baseUrl: config.request?.baseUrl,
          headers: config.request?.headers,
          query: config.request?.query,
          body: config.request?.body,
          contentType: config.request?.contentType,
          userAgent: config.request?.userAgent,
        )

        logger.debug("resolveMediaUrl: calling transform callback...")
        let outerPromise = transform(baseRequest, nil)
        logger.debug("resolveMediaUrl: awaiting outer promise...")
        let innerPromise = try await outerPromise.await()
        logger.debug("resolveMediaUrl: awaiting inner promise...")
        let transformedConfig = try await innerPromise.await()
        logger.debug("resolveMediaUrl: transform complete")

        // Extract values immediately to Swift native types to avoid
        // memory corruption in Nitro's Swift-C++ bridge when the
        // Promise<RequestConfig> is deallocated
        let finalUrl = buildUrl(from: transformedConfig)
        let headers = transformedConfig.headers
        let userAgent = transformedConfig.userAgent

        logger.debug("Media URL transformed: \(originalUrl) -> \(finalUrl)")

        return MediaResolvedUrl(
          url: finalUrl,
          headers: headers,
          userAgent: userAgent,
        )
      } catch {
        logger.error("Media transform failed: \(error.localizedDescription)")
        return MediaResolvedUrl(url: originalUrl, headers: nil, userAgent: nil)
      }
    }

    // No transform, just apply baseUrl if configured
    let baseUrl = mediaConfig.baseUrl ?? config.request?.baseUrl
    if let baseUrl {
      let finalUrl = BrowserPathHelper.buildUrl(baseUrl: baseUrl, path: originalUrl)
      logger.debug("Media URL with baseUrl: \(originalUrl) -> \(finalUrl)")
      return MediaResolvedUrl(
        url: finalUrl,
        headers: mediaConfig.headers ?? config.request?.headers,
        userAgent: mediaConfig.userAgent ?? config.request?.userAgent,
      )
    }

    return MediaResolvedUrl(url: originalUrl, headers: nil, userAgent: nil)
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
      // Create base config from global request config
      var mergedConfig = RequestConfig(
        method: config.request?.method,
        path: track.artwork, // Use track.artwork as default path
        baseUrl: config.request?.baseUrl,
        headers: config.request?.headers,
        query: config.request?.query,
        body: config.request?.body,
        contentType: config.request?.contentType,
        userAgent: config.request?.userAgent,
      )

      if let resolve = artworkConfig.resolve {
        let outerPromise = resolve(track)
        let innerPromise = try await outerPromise.await()
        let resolvedConfig = try await innerPromise.await()

        mergedConfig = mergeRequestConfig(base: mergedConfig, override: extractConfig(resolvedConfig))
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

      // Skip transform at browse-time (no size context) — applied at load-time
      let hasSize = imageContext?.width != nil || imageContext?.height != nil
      if let transform = artworkConfig.transform, hasSize {
        let outerPromise = transform(MediaTransformParams(request: mergedConfig, context: imageContext))
        let innerPromise = try await outerPromise.await()
        let transformedConfig = try await innerPromise.await()
        mergedConfig = extractConfig(transformedConfig)
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

  /// Extracts all values from a RequestConfig into a new instance to avoid
  /// memory corruption in Nitro's Swift-C++ bridge when the Promise is deallocated.
  private func extractConfig(_ config: RequestConfig) -> RequestConfig {
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
