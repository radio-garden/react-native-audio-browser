import CarPlay
import Foundation
import NitroModules
import os.log

/// Handles all image loading and rendering for CarPlay.
///
/// Responsibilities:
/// - SF Symbol rendering, with light/dark variants
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

  /// Renders an SF Symbol from an artwork string (e.g. "sf:heart.fill?bg=#000&fg=#fff"). With an
  /// `fg` it is drawn in that color; without one it is black or white by appearance, over a grey
  /// tile.
  private func sfSymbolImage(forArtwork artwork: String, canvasSize: CGSize) -> UIImage? {
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

  /// A track's artwork at `size` points, through the artwork transform when one is configured;
  /// nil when it has none or the load fails.
  /// - Parameter style: the track's resolved style block; nil for the track's own declaration,
  ///   when there is no section to inherit from (tabs, Up Next).
  func artwork(for track: Track, style: TrackStyle? = nil, size: CGSize) async -> UIImage? {
    // Each variant goes through the whole single-URL path (route config, headers, SVG, the
    // resolve transform), so the pair cannot drift from it.
    if let variants = track.artwork?.variants {
      return await artwork(for: track, style: style, variants: variants, size: size)
    }

    let artwork = track.artwork?.url
    var resolved: ArtworkResolvedImage?
    if CarPlayArtworkResolver.needsResolve(artwork: artwork), let browserManager {
      let scale = Double(carTraitCollection.displayScale)
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
      return sfSymbolImage(forArtwork: artwork, canvasSize: CGSize(width: width, height: height))
    case let .fetch(uri, headers, shouldTint, isSvg):
      let image = await fetchImage(uri: uri, headers: headers, isSvg: isSvg, size: size)
      guard let image, shouldTint else { return image }
      return tinted(image)
    case .none:
      return nil
    }
  }

  // MARK: - Private

  /// Both images of an `ArtworkVariants` pair as one adaptive image, which CarPlay re-resolves
  /// in place when the appearance changes. When one half fails, the other is used alone.
  private func artwork(for track: Track, style: TrackStyle?, variants: ArtworkVariants, size: CGSize) async -> UIImage? {
    async let light = artwork(for: track.copying(artwork: .some(.first(variants.light))), style: style, size: size)
    async let dark = artwork(for: track.copying(artwork: .some(.first(variants.dark))), style: style, size: size)
    let (lightImage, darkImage) = await (light, dark)
    guard let lightImage, let darkImage else { return lightImage ?? darkImage }
    return .adaptive(light: lightImage, dark: darkImage, for: carTraitCollection)
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

  /// A monochrome image drawn black for light appearance and white for dark.
  private func tinted(_ image: UIImage) -> UIImage {
    .adaptive(
      light: renderImageToBitmap(image, tintColor: .black),
      dark: renderImageToBitmap(image, tintColor: .white),
      for: carTraitCollection,
    )
  }
}
