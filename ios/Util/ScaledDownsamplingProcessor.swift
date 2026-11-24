import CoreGraphics
import Kingfisher

/// DownsamplingImageProcessor with the scale in its identifier, which names the cache entry:
/// Kingfisher's names only the size. Pass the same scale as `.scaleFactor`.
struct ScaledDownsamplingProcessor: ImageProcessor {
  let identifier: String
  private let downsampling: DownsamplingImageProcessor

  init(size: CGSize, scale: CGFloat) {
    downsampling = DownsamplingImageProcessor(size: size)
    identifier = "\(downsampling.identifier)@\(scale)x"
  }

  func process(item: ImageProcessItem, options: KingfisherParsedOptionsInfo) -> KFCrossPlatformImage? {
    downsampling.process(item: item, options: options)
  }
}
