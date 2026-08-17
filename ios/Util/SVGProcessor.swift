import Kingfisher
import SwiftDraw
import UIKit

/// Kingfisher processor that renders SVG data to UIImage using SwiftDraw.
struct SVGProcessor: ImageProcessor {
  /// Kingfisher keys its cache on `identifier` alone (`ImageCache.computedKey`),
  /// and `.scaleFactor` never enters that key — so the render parameters have to,
  /// or two different rasterizations of the same URL would collide. Today only
  /// CarPlay rasterizes SVGs (one screen, one scale), so this is insurance
  /// against a second call site rather than a live fix.
  var identifier: String {
    "com.audiobrowser.svgprocessor(\(size?.width ?? -1)x\(size?.height ?? -1)@\(scale))"
  }

  /// Target size for rendering (in points). If nil, uses SVG's intrinsic size.
  let size: CGSize?

  /// Scale factor for rendering. Resolved at init time to avoid MainActor issues.
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
      // Already an image, return as-is
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

// MARK: - Kingfisher Options Extension

extension KingfisherOptionsInfoItem {
  /// Returns an SVG processor option for Kingfisher.
  @MainActor
  static func svgProcessor(size: CGSize? = nil, scale: CGFloat? = nil) -> KingfisherOptionsInfoItem {
    .processor(SVGProcessor(size: size, scale: scale))
  }
}
