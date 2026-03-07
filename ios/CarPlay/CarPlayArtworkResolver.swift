import Foundation

/// Represents a resolved artwork image URL with optional headers.
struct ArtworkResolvedImage: Equatable, Sendable {
  let uri: String
  let headers: [String: String]?
}

/// Describes what action to take for loading artwork.
/// Produced by `CarPlayArtworkResolver`, consumed by `CarPlayImageLoader`.
enum ArtworkLoadAction: Equatable, Sendable {
  /// Render an SF Symbol from the artwork string at the given canvas size
  case sfSymbol(artwork: String, width: Double, height: Double)
  /// Fetch an image from a URL, optionally with headers, tinting, and SVG processing
  case fetch(uri: String, headers: [String: String]?, shouldTint: Bool, isSvg: Bool)
  /// No artwork available
  case none
}

/// Determines artwork loading strategy without UIKit dependencies.
///
/// Encapsulates the decision tree for how to load artwork:
/// 1. SF Symbol artwork → render directly
/// 2. URL resolver available → resolve, then fetch from resolved URL
/// 3. Fallback → fetch directly from artwork/artworkSource URL
enum CarPlayArtworkResolver {
  /// Resolves the artwork loading strategy for a track.
  ///
  /// - Parameters:
  ///   - artwork: The track's `artwork` field (may be an SF Symbol string or URL)
  ///   - artworkSourceUri: The track's `artworkSource?.uri` field
  ///   - artworkCarPlayTinted: Whether to apply light/dark tinting
  ///   - targetWidth: Target width in points
  ///   - targetHeight: Target height in points
  ///   - displayScale: The CarPlay display scale factor
  ///   - urlResolver: Optional closure that resolves artwork URLs (wraps BrowserManager)
  static func resolve(
    artwork: String?,
    artworkSourceUri: String?,
    artworkCarPlayTinted: Bool?,
    targetWidth: Double,
    targetHeight: Double,
    displayScale: Double,
    urlResolver: ((Double, Double) async -> ArtworkResolvedImage?)?
  ) async -> ArtworkLoadAction {
    // 1. Primary SF symbol check
    if let artwork, artwork.hasPrefix("sf:") {
      return .sfSymbol(artwork: artwork, width: targetWidth, height: targetHeight)
    }

    // 2. Try URL resolution via resolver
    if let urlResolver {
      let pixelWidth = targetWidth * displayScale
      let pixelHeight = targetHeight * displayScale
      if let resolved = await urlResolver(pixelWidth, pixelHeight) {
        guard let resolvedUrl = URL(string: resolved.uri) else {
          return resolveDirectFallback(
            artwork: artwork,
            artworkSourceUri: artworkSourceUri,
            artworkCarPlayTinted: artworkCarPlayTinted,
            targetWidth: targetWidth,
            targetHeight: targetHeight
          )
        }
        let isSvg = resolvedUrl.pathExtension.lowercased() == "svg"
        return .fetch(
          uri: resolved.uri,
          headers: resolved.headers,
          shouldTint: artworkCarPlayTinted ?? false,
          isSvg: isSvg
        )
      }
    }

    // 3. Direct fallback
    return resolveDirectFallback(
      artwork: artwork,
      artworkSourceUri: artworkSourceUri,
      artworkCarPlayTinted: artworkCarPlayTinted,
      targetWidth: targetWidth,
      targetHeight: targetHeight
    )
  }

  private static func resolveDirectFallback(
    artwork: String?,
    artworkSourceUri: String?,
    artworkCarPlayTinted: Bool?,
    targetWidth: Double,
    targetHeight: Double
  ) -> ArtworkLoadAction {
    guard let directUri = artwork ?? artworkSourceUri else { return .none }

    if directUri.hasPrefix("sf:") {
      return .sfSymbol(artwork: directUri, width: targetWidth, height: targetHeight)
    }

    guard let directUrl = URL(string: directUri) else { return .none }
    let isSvg = directUrl.pathExtension.lowercased() == "svg"
    return .fetch(
      uri: directUri,
      headers: nil,
      shouldTint: artworkCarPlayTinted ?? false,
      isSvg: isSvg
    )
  }
}
