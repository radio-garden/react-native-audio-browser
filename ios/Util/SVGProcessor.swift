import Kingfisher
import SwiftDraw
import UIKit

/// Kingfisher processor that rasterizes SVG data with SwiftDraw.
struct SVGProcessor: ImageProcessor {
  // Kingfisher keys its cache on the URL and this identifier only (`.scaleFactor` is
  // not in the key), so every render parameter must be here or different
  // rasterizations of one URL collide.
  var identifier: String {
    "com.audiobrowser.svgprocessor(\(size.width)x\(size.height)@\(scale))"
  }

  /// The slot in points; the SVG fills as much of it as its aspect ratio allows.
  let size: CGSize

  let scale: CGFloat

  func process(item: ImageProcessItem, options _: KingfisherParsedOptionsInfo) -> KFCrossPlatformImage? {
    switch item {
    case let .data(data):
      return renderSVG(from: data)
    case let .image(image):
      return image
    @unknown default:
      return nil
    }
  }

  private func renderSVG(from data: Data) -> UIImage? {
    guard let svg = SVG(data: data) else {
      return nil
    }

    return svg.rasterize(size: svg.size.aspectFitted(in: size), scale: scale)
  }
}
