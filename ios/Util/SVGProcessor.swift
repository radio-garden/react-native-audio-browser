import Kingfisher
import SwiftDraw
import UIKit

/// Kingfisher processor that rasterizes SVG data with SwiftDraw.
struct SVGProcessor: ImageProcessor {
  // Kingfisher keys its cache on the URL and this identifier only (`.scaleFactor` is
  // not in the key), so every render parameter must be here or different
  // rasterizations of one URL collide.
  var identifier: String {
    "com.audiobrowser.svgprocessor(\(size?.width ?? -1)x\(size?.height ?? -1)@\(scale))"
  }

  /// Points; nil renders at the SVG's intrinsic size.
  let size: CGSize?

  let scale: CGFloat

  @MainActor
  init(size: CGSize? = nil, scale: CGFloat? = nil) {
    self.size = size
    self.scale = scale ?? appDisplayScale
  }

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

    let renderSize: CGSize = if let size {
      size
    } else {
      svg.size
    }

    return svg.rasterize(size: renderSize, scale: scale)
  }
}
