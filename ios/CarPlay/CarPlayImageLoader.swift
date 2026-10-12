import CarPlay
import Foundation
import NitroModules
import os.log

/// Handles all image loading and rendering for CarPlay.
///
/// Responsibilities:
/// - SF Symbol rendering (plain and canvas-rendered with light/dark variants)
/// - Artwork loading via BrowserManager's artwork URL resolution
/// - Direct artwork loading as fallback
/// - Adaptive image tinting for monochrome icons
/// - Placeholder image generation
@MainActor
final class CarPlayImageLoader {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "CarPlayImageLoader")

  private let carTraitCollection: UITraitCollection
  private weak var browserManager: BrowserManager?

  init(carTraitCollection: UITraitCollection, browserManager: BrowserManager?) {
    self.carTraitCollection = carTraitCollection
    self.browserManager = browserManager
  }

  // MARK: - SF Symbols

  /// Creates an SF Symbol image for tabs - plain systemName, CarPlay handles tinting
  func sfSymbolImage(_ symbolName: String) -> UIImage? {
    UIImage(systemName: symbolName)
  }

  /// Renders an SF Symbol from an artwork string (e.g. "sf:heart.fill?bg=#000&fg=#fff"). With an
  /// `fg` it is drawn in that color; without one it is black or white by appearance, over a grey
  /// tile.
  func sfSymbolImage(forArtwork artwork: String, canvasSize: CGSize) -> UIImage? {
    let (name, background, tint) = SFSymbolRenderer.parseArtwork(artwork)
    let scale = carTraitCollection.displayScale
    let lightTile = UIColor(white: 0.92, alpha: 1)
    let darkTile = UIColor(white: 0.15, alpha: 1)
    if let tint {
      return SFSymbolRenderer.image(
        name: name, canvasSize: canvasSize, scale: scale, tint: tint,
        background: background ?? (tint == .white ? darkTile : lightTile),
      )
    }
    guard
      let light = SFSymbolRenderer.image(
        name: name, canvasSize: canvasSize, scale: scale, tint: .black, background: lightTile,
      ),
      let dark = SFSymbolRenderer.image(
        name: name, canvasSize: canvasSize, scale: scale, tint: .white, background: darkTile,
      )
    else { return nil }
    return .adaptive(light: light, dark: dark, for: carTraitCollection)
  }

  /// Cached empty placeholders, keyed by point size — list items and image-row
  /// cells reserve different footprints.
  private var placeholders: [String: UIImage] = [:]

  /// Empty placeholder image reserving a `size`-point slot while artwork loads.
  func placeholderImage(size: CGSize) -> UIImage? {
    let key = "\(size.width)×\(size.height)"
    if let cached = placeholders[key] { return cached }
    UIGraphicsBeginImageContextWithOptions(size, false, carTraitCollection.displayScale)
    defer { UIGraphicsEndImageContext() }
    guard let image = UIGraphicsGetImageFromCurrentImageContext() else { return nil }
    placeholders[key] = image
    return image
  }

  // MARK: - Artwork Loading

  /// Loads artwork for a track with size context, using the artwork transform if configured.
  /// - Parameters:
  ///   - track: The track to load artwork for
  ///   - style: The track's resolved style block (inherited values folded in).
  ///     nil = the track's own declaration — callers without a section context
  ///     (tabs, now-playing) have no inheritance to resolve.
  ///   - size: The target size in points (will be multiplied by CarPlay display scale)
  ///   - completion: Called with the loaded image, or nil on failure
  func loadArtwork(for track: Track, style: TrackStyle? = nil, size: CGSize, completion: @escaping @Sendable (UIImage?) -> Void) {
    // A track that ships one image per appearance is loaded as two ordinary
    // single-URL loads and combined below. Recursing rather than branching
    // inside the loader means each variant still goes through the whole
    // pipeline — route config, headers, SVG rasterising, the resolve transform
    // — instead of a second, thinner fetch path that would drift from it.
    if let variants = track.artwork?.variants {
      loadVariants(for: track, style: style, variants: variants, size: size, completion: completion)
      return
    }

    let artwork = track.artwork?.url
    Task {
      var resolved: ArtworkResolvedImage?
      if CarPlayArtworkResolver.needsResolve(artwork: artwork), let browserManager = self.browserManager {
        let scale = Double(self.carTraitCollection.displayScale)
        let context = ImageContext(width: Double(size.width) * scale, height: Double(size.height) * scale)
        resolved = await browserManager.requests.resolveArtworkUrl(track: track, perRouteConfig: nil, imageContext: context)
          .map { ArtworkResolvedImage(uri: $0.uri, headers: $0.headers) }
      }
      let action = CarPlayArtworkResolver.action(
        artwork: artwork,
        artworkSource: track.artworkSource.map { ArtworkResolvedImage(uri: $0.uri, headers: $0.headers) },
        resolved: resolved,
        stencil: (style ?? track.style)?.artworkRendering == .stencil,
        width: Double(size.width),
        height: Double(size.height),
      )

      switch action {
      case let .sfSymbol(artwork, width, height):
        completion(self.sfSymbolImage(forArtwork: artwork, canvasSize: CGSize(width: width, height: height)))
      case let .fetch(uri, headers, shouldTint, isSvg):
        let image = await self.fetchImage(uri: uri, headers: headers, isSvg: isSvg, size: size)
        if let image, shouldTint {
          completion(self.createAdaptiveImage(image, carTraitCollection: self.carTraitCollection))
        } else {
          completion(image)
        }
      case .none:
        completion(nil)
      }
    }
  }

  // MARK: - Private

  /// Loads both images of an `ArtworkVariants` pair and returns them as one
  /// adaptive image.
  ///
  /// The pair is registered on a `UIImageAsset`, which is what makes an
  /// appearance change mid-drive re-resolve in place: CarPlay re-reads the
  /// asset against the new trait collection, so the row swaps image without
  /// another fetch or a re-query of the browse tree.
  ///
  /// If either half fails to load, the other is used alone rather than dropping
  /// the row's image entirely — a glyph that is wrong for one appearance still
  /// beats an empty slot, and this is usually a transient fetch failure.
  private func loadVariants(
    for track: Track,
    style: TrackStyle?,
    variants: ArtworkVariants,
    size: CGSize,
    completion: @escaping @Sendable (UIImage?) -> Void,
  ) {
    Task {
      async let light = self.loadArtworkAsync(
        for: track.copying(artwork: .some(.first(variants.light))), style: style, size: size,
      )
      async let dark = self.loadArtworkAsync(
        for: track.copying(artwork: .some(.first(variants.dark))), style: style, size: size,
      )
      let (lightImage, darkImage) = await (light, dark)

      guard let lightImage, let darkImage else {
        completion(lightImage ?? darkImage)
        return
      }

      completion(.adaptive(light: lightImage, dark: darkImage, for: self.carTraitCollection))
    }
  }

  /// `loadArtwork` as an awaitable, so the two variants can load concurrently.
  private func loadArtworkAsync(for track: Track, style: TrackStyle?, size: CGSize) async -> UIImage? {
    await withCheckedContinuation { continuation in
      self.loadArtwork(for: track, style: style, size: size) { image in
        continuation.resume(returning: image)
      }
    }
  }

  /// Fetches an image from a URL with optional headers and SVG processing.
  /// Rasters are downsampled to `size` points at the car's display scale —
  /// CPListItem.h: "size them to the display scale of the car screen". SVGs
  /// already rasterize at car scale via SVGProcessor.
  private func fetchImage(uri: String, headers: [String: String]?, isSvg: Bool, size: CGSize) async -> UIImage? {
    let source = ImageSource(uri: uri, method: nil, headers: headers, body: nil)
    let scale = carTraitCollection.displayScale
    if isSvg {
      return await ArtworkImageFetcher.fetchImage(from: source, svgScale: scale)
    }
    return await ArtworkImageFetcher.fetchImage(from: source, downsampleTo: (size, scale))
  }

  /// Renders an image to a bitmap with the specified tint color (for monochrome icons).
  private nonisolated func renderImageToBitmap(_ image: UIImage, tintColor: UIColor) -> UIImage {
    UIGraphicsBeginImageContextWithOptions(image.size, false, image.scale)
    defer { UIGraphicsEndImageContext() }

    tintColor.set()
    image.withRenderingMode(.alwaysTemplate).draw(in: CGRect(origin: .zero, size: image.size))

    guard let rendered = UIGraphicsGetImageFromCurrentImageContext() else {
      return image
    }
    return rendered.withRenderingMode(.alwaysOriginal)
  }

  /// Creates light/dark tinted variants of an image and returns the appropriate one for current appearance
  private nonisolated func createAdaptiveImage(_ image: UIImage, carTraitCollection: UITraitCollection) -> UIImage {
    .adaptive(
      light: renderImageToBitmap(image, tintColor: .black),
      dark: renderImageToBitmap(image, tintColor: .white),
      for: carTraitCollection,
    )
  }
}
