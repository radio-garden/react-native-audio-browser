import Kingfisher
import SwiftDraw
import UIKit

/// Kingfisher processor that renders SVG data to UIImage using SwiftDraw.
struct SVGProcessor: ImageProcessor {
  let identifier = "com.audiobrowser.svgprocessor"

  /// Target size for rendering (in points). If nil, uses SVG's intrinsic size.
  let size: CGSize?

  /// Scale factor for rendering. Resolved at init time to avoid MainActor issues.
  let scale: CGFloat

  @MainActor
  init(size: CGSize? = nil, scale: CGFloat? = nil) {
    self.size = size
    self.scale = scale ?? UIScreen.main.scale
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
