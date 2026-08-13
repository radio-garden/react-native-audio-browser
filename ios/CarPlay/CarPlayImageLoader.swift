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

  /// Renders an SF Symbol from an artwork string (e.g. "sf:heart.fill?bg=#000&fg=#fff").
  func sfSymbolImage(forArtwork artwork: String, canvasSize: CGSize) -> UIImage? {
    let (symbolName, bg, fg) = SFSymbolRenderer.parseArtwork(artwork)
    return sfSymbolImageForSize(symbolName, canvasSize: canvasSize, backgroundColor: bg, symbolColor: fg)
  }

  /// Cached empty placeholder image to reserve space while artwork loads
  lazy var placeholderImage: UIImage? = {
    let size = CPListItem.maximumImageSize
    let scale = carTraitCollection.displayScale
    UIGraphicsBeginImageContextWithOptions(size, false, scale)
    defer { UIGraphicsEndImageContext() }
    return UIGraphicsGetImageFromCurrentImageContext()
  }()

  // MARK: - Artwork Loading

  /// Loads artwork for a track with size context, using the artwork transform if configured.
  /// - Parameters:
  ///   - track: The track to load artwork for
  ///   - size: The target size in points (will be multiplied by CarPlay display scale)
  ///   - completion: Called with the loaded image, or nil on failure
  func loadArtwork(for track: Track, size: CGSize, completion: @escaping @Sendable (UIImage?) -> Void) {
    // A track that ships one image per appearance is loaded as two ordinary
    // single-URL loads and combined below. Recursing rather than branching
    // inside the loader means each variant still goes through the whole
    // pipeline — route config, headers, SVG rasterising, the resolve transform
    // — instead of a second, thinner fetch path that would drift from it.
    if let variants = track.artwork?.variants {
      loadVariants(for: track, variants: variants, size: size, completion: completion)
      return
    }

    // Build URL resolver closure that wraps BrowserManager
    let urlResolver: ((Double, Double) async -> ArtworkResolvedImage?)? = browserManager.map { bm in
      { [weak bm] pixelWidth, pixelHeight in
        guard let bm else { return nil }
        let imageContext = ImageContext(width: pixelWidth, height: pixelHeight)
        guard let source = await bm.resolveArtworkUrl(track: track, perRouteConfig: nil, imageContext: imageContext) else { return nil }
        return ArtworkResolvedImage(uri: source.uri, headers: source.headers)
      }
    }

    Task {
      let action = await CarPlayArtworkResolver.resolve(
        artwork: track.artwork?.url,
        artworkSourceUri: track.artworkSource?.uri,
        artworkCarPlayTinted: track.artworkCarPlayTinted,
        targetWidth: Double(size.width),
        targetHeight: Double(size.height),
        displayScale: Double(self.carTraitCollection.displayScale),
        urlResolver: urlResolver,
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
    variants: ArtworkVariants,
    size: CGSize,
    completion: @escaping @Sendable (UIImage?) -> Void,
  ) {
    Task {
      async let light = self.loadArtworkAsync(for: track.copying(artwork: .some(.first(variants.light))), size: size)
      async let dark = self.loadArtworkAsync(for: track.copying(artwork: .some(.first(variants.dark))), size: size)
      let (lightImage, darkImage) = await (light, dark)

      guard let lightImage, let darkImage else {
        completion(lightImage ?? darkImage)
        return
      }

      let asset = UIImageAsset()
      asset.register(lightImage, with: UITraitCollection(userInterfaceStyle: .light))
      asset.register(darkImage, with: UITraitCollection(userInterfaceStyle: .dark))
      completion(asset.image(with: self.carTraitCollection))
    }
  }

  /// `loadArtwork` as an awaitable, so the two variants can load concurrently.
  private func loadArtworkAsync(for track: Track, size: CGSize) async -> UIImage? {
    await withCheckedContinuation { continuation in
      self.loadArtwork(for: track, size: size) { image in
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

  /// Creates an SF Symbol image rendered at the given canvas size.
  /// When explicit colors are provided, uses them directly.
  /// Otherwise falls back to automatic light/dark variants.
  private func sfSymbolImageForSize(_ symbolName: String, canvasSize: CGSize, backgroundColor: UIColor? = nil, symbolColor: UIColor? = nil) -> UIImage? {
    let scale = carTraitCollection.displayScale
    let symbolPointSize = min(canvasSize.width, canvasSize.height) * 0.45

    let config = UIImage.SymbolConfiguration(pointSize: symbolPointSize, weight: .medium)
    guard let symbol = UIImage(systemName: symbolName, withConfiguration: config) else { return nil }

    // Explicit colors: single image, no light/dark variants needed
    if let symbolColor {
      return renderSymbolInCanvas(symbol, tintColor: symbolColor, backgroundColor: backgroundColor, canvasSize: canvasSize, scale: scale)
    }

    // Auto light/dark variants
    let lightImage = renderSymbolInCanvas(symbol, tintColor: .black, backgroundColor: nil, canvasSize: canvasSize, scale: scale)
    let darkImage = renderSymbolInCanvas(symbol, tintColor: .white, backgroundColor: nil, canvasSize: canvasSize, scale: scale)

    let asset = UIImageAsset()
    asset.register(lightImage, with: UITraitCollection(userInterfaceStyle: .light))
    asset.register(darkImage, with: UITraitCollection(userInterfaceStyle: .dark))

    return asset.image(with: carTraitCollection)
  }

  /// Renders an SF Symbol centered in a canvas with a background fill.
  /// When `backgroundColor` is nil, derives a default from the tint color (light/dark).
  private nonisolated func renderSymbolInCanvas(_ symbol: UIImage, tintColor: UIColor, backgroundColor: UIColor?, canvasSize: CGSize, scale: CGFloat) -> UIImage {
    let renderer = UIGraphicsImageRenderer(size: canvasSize, format: {
      let fmt = UIGraphicsImageRendererFormat()
      fmt.scale = scale
      return fmt
    }())

    return renderer.image { _ in
      let bgColor = backgroundColor ?? (tintColor == .white ? UIColor(white: 0.15, alpha: 1) : UIColor(white: 0.92, alpha: 1))
      bgColor.setFill()
      UIRectFill(CGRect(origin: .zero, size: canvasSize))

      let symbolSize = symbol.size
      let x = (canvasSize.width - symbolSize.width) / 2
      let y = (canvasSize.height - symbolSize.height) / 2

      tintColor.set()
      symbol.withRenderingMode(.alwaysTemplate).draw(in: CGRect(x: x, y: y, width: symbolSize.width, height: symbolSize.height))
    }.withRenderingMode(.alwaysOriginal)
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
    let lightImage = renderImageToBitmap(image, tintColor: .black)
    let darkImage = renderImageToBitmap(image, tintColor: .white)

    let asset = UIImageAsset()
    asset.register(lightImage, with: UITraitCollection(userInterfaceStyle: .light))
    asset.register(darkImage, with: UITraitCollection(userInterfaceStyle: .dark))

    return asset.image(with: carTraitCollection)
  }
}
