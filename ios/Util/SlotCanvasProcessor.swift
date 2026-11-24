import CoreGraphics
import Kingfisher
#if canImport(UIKit)
  import UIKit
#else
  import AppKit
#endif

/// Draws the image, aspect-fitted and centred, onto a transparent canvas of exactly `slot`
/// points at `scale`: CarPlay sizes a list or tile image by its points. A non-square image is
/// letterboxed and a smaller one scaled up. A slot with no area leaves the image as it is.
struct SlotCanvasProcessor: ImageProcessor {
  let identifier: String
  private let slot: CGSize
  private let scale: CGFloat

  init(slot: CGSize, scale: CGFloat) {
    self.slot = slot
    self.scale = scale
    identifier = "com.audiobrowser.SlotCanvasProcessor(\(slot)@\(scale)x)"
  }

  func process(item: ImageProcessItem, options: KingfisherParsedOptionsInfo) -> KFCrossPlatformImage? {
    let image: KFCrossPlatformImage? = switch item {
    case let .image(image): image
    case let .data(data): KingfisherWrapper.image(data: data, options: ImageCreatingOptions(scale: options.scaleFactor))
    @unknown default: nil
    }
    guard let image else { return nil }
    return canvas(for: image) ?? image
  }

  private func canvas(for image: KFCrossPlatformImage) -> KFCrossPlatformImage? {
    let width = Int((slot.width * scale).rounded())
    let height = Int((slot.height * scale).rounded())
    guard width > 0, height > 0, let source = Self.cgImage(of: image),
          let rect = SlotFit.drawRect(for: CGSize(width: source.width, height: source.height), in: slot),
          let context = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue,
          )
    else { return nil }
    context.interpolationQuality = .high
    context.scaleBy(x: scale, y: scale)
    context.draw(source, in: rect)
    guard let drawn = context.makeImage() else { return nil }
    #if canImport(UIKit)
      return UIImage(cgImage: drawn, scale: scale, orientation: .up)
    #else
      return NSImage(cgImage: drawn, size: .zero)
    #endif
  }

  private static func cgImage(of image: KFCrossPlatformImage) -> CGImage? {
    #if canImport(UIKit)
      image.cgImage
    #else
      image.cgImage(forProposedRect: nil, context: nil, hints: nil)
    #endif
  }
}
