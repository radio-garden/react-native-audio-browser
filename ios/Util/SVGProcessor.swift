import Kingfisher
import SwiftDraw
#if canImport(UIKit)
  import UIKit
#else
  import AppKit
#endif

/// Kingfisher processor that rasterizes SVG data with SwiftDraw, and decodes any other bytes as
/// Kingfisher's default processor does: a URL with an `.svg` path may serve a raster image.
struct SVGProcessor: ImageProcessor {
  // Kingfisher keys its cache on the URL and this identifier only (`.scaleFactor` is
  // not in the key), so every render parameter must be here or different
  // rasterizations of one URL collide.
  var identifier: String {
    "com.audiobrowser.svgprocessor(\(slot.width)x\(slot.height)@\(scale))"
  }

  /// Points; the SVG fills as much of it as its aspect ratio allows.
  let slot: CGSize

  let scale: CGFloat

  func process(item: ImageProcessItem, options: KingfisherParsedOptionsInfo) -> KFCrossPlatformImage? {
    switch item {
    case let .data(data):
      return renderSVG(from: data) ?? DefaultImageProcessor.default.process(item: item, options: options)
    case let .image(image):
      return image
    @unknown default:
      return nil
    }
  }

  /// Nil for an SVG that declares no usable size, which SwiftDraw cannot scale to the slot.
  private func renderSVG(from data: Data) -> KFCrossPlatformImage? {
    guard let svg = SVG(data: data), let size = svg.size.aspectFitted(in: slot) else {
      return nil
    }

    #if canImport(UIKit)
      return svg.rasterize(size: size, scale: scale)
    #else
      return svg.rasterize(with: size, scale: scale)
    #endif
  }
}
