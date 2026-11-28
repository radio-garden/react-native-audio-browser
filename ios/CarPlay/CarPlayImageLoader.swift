import CarPlay
import Foundation
import NitroModules
import os.log

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

  /// Renders an `sf:` artwork string (e.g. "sf:heart.fill?bg=#000&fg=#fff"). With `fg` it is
  /// drawn in that color; without, black or white by appearance over a grey tile.
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

  private var placeholders: [String: UIImage] = [:]

  /// An empty image that reserves a `size`-point slot while artwork loads.
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

  /// A track's artwork at `size` points, through the artwork resolve transform when one is
  /// configured, fetched once; nil when it has none or its load fails.
  func image(for track: Track, size: CGSize) async -> UIImage? {
    var latest: UIImage?
    await loadArtwork(for: track, size: size, waits: nil) { latest = $0 }
    return latest
  }

  /// Hands `apply` a track's artwork at `size` points: one image, or a light and dark pair as
  /// one adaptive image, which CarPlay re-resolves in place when the appearance changes
  /// (`ArtworkSlotLoader`). Returns whether any image was applied.
  /// - Parameter style: the track's resolved style; nil uses the track's own, for places with
  ///   no section to inherit from (tabs, Up Next).
  /// - Parameter waits: Given, a failed load is retried (`ArtworkRetry`); nil loads once.
  @discardableResult
  func loadArtwork(
    for track: Track, style: TrackStyle? = nil, size: CGSize, waits: ArtworkRetryWaits?,
    apply: @escaping @MainActor (UIImage) -> Void,
  ) async -> Bool {
    let variants = track.artwork?.variants
    return await ArtworkSlotLoader.load(
      pair: variants != nil,
      waits: waits,
      attempt: { [weak self] half in
        // Each variant runs the whole single-URL path (route config, headers, SVG, the resolve
        // transform), so the pair cannot drift from it.
        let halfTrack = switch (half, variants) {
        case let (.light, variants?): track.copying(artwork: .some(.first(variants.light)))
        case let (.dark, variants?): track.copying(artwork: .some(.first(variants.dark)))
        default: track
        }
        return await self?.attempt(for: halfTrack, style: style, size: size)
      },
      combine: { [carTraitCollection] light, dark in .adaptive(light: light, dark: dark, for: carTraitCollection) },
      apply: apply,
    )
  }

  /// One whole load of a single-URL artwork: the resolve transform, then the fetch. Nil when the
  /// track has nothing to load.
  private func attempt(for track: Track, style: TrackStyle?, size: CGSize) async -> Result<UIImage, ArtworkLoadFailure>? {
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
      return sfSymbolImage(forArtwork: artwork, canvasSize: CGSize(width: width, height: height)).map { .success($0) }
    case let .fetch(uri, headers, shouldTint):
      let source = ImageSource(uri: uri, method: nil, headers: headers, body: nil)
      // A canvas of exactly `size` at the car's scale (CPListItem.h: "size them to the display
      // scale of the car screen"), whatever size the source arrives at.
      let slot = (size, carTraitCollection.displayScale)
      let result = await ArtworkImageFetcher.load(from: source, slot: slot, onCanvas: true)
      return shouldTint ? result.map(tinted) : result
    case .none:
      return nil
    }
  }

  // MARK: - Private

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

  private func tinted(_ image: UIImage) -> UIImage {
    .adaptive(
      light: renderImageToBitmap(image, tintColor: .black),
      dark: renderImageToBitmap(image, tintColor: .white),
      for: carTraitCollection,
    )
  }
}
