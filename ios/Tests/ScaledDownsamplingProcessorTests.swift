import CoreGraphics
import Foundation
import ImageIO
import Kingfisher
import Testing

@testable import AudioBrowserTestable

@Suite("ScaledDownsamplingProcessor")
struct ScaledDownsamplingProcessorTests {
  private let slot = CGSize(width: 44, height: 44)
  private let cache = ImageCache(name: "scaled-downsampling-\(UUID().uuidString)")
  private let source = Source.provider(PNGProvider(side: 200))

  private func fetchedPixelWidth(scale: CGFloat) async throws -> Int? {
    let result = try await KingfisherManager.shared.retrieveImage(
      with: source,
      options: [
        .targetCache(cache),
        .processor(ScaledDownsamplingProcessor(size: slot, scale: scale)),
        .scaleFactor(scale),
        .cacheOriginalImage,
        .waitForCache,
      ],
    )
    return result.image.pixelWidth
  }

  @Test func anImageCachedAtOneScaleIsDownsampledAgainForAnother() async throws {
    let threeX = try await fetchedPixelWidth(scale: 3)
    let twoX = try await fetchedPixelWidth(scale: 2)
    #expect(threeX == 132)
    #expect(twoX == 88)
  }

  @Test func anImageOnDiskAtOneScaleIsDownsampledAgainForAnother() async throws {
    let threeX = try await fetchedPixelWidth(scale: 3)
    cache.clearMemoryCache()
    let twoX = try await fetchedPixelWidth(scale: 2)
    #expect(threeX == 132)
    #expect(twoX == 88)
  }

  @Test func theSameSlotAndScaleShareOneCacheEntry() {
    #expect(
      ScaledDownsamplingProcessor(size: slot, scale: 2).identifier
        == ScaledDownsamplingProcessor(size: slot, scale: 2).identifier,
    )
  }
}

private struct PNGProvider: ImageDataProvider {
  let side: Int
  var cacheKey: String { "png-\(side)" }

  func data(handler: @escaping @Sendable (Result<Data, any Error>) -> Void) {
    let context = CGContext(
      data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0,
      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue,
    )!
    context.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: side, height: side))
    let data = NSMutableData()
    let destination = CGImageDestinationCreateWithData(data, "public.png" as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, context.makeImage()!, nil)
    CGImageDestinationFinalize(destination)
    handler(.success(data as Data))
  }
}

private extension KFCrossPlatformImage {
  var pixelWidth: Int? {
    #if canImport(UIKit)
      cgImage?.width
    #else
      cgImage(forProposedRect: nil, context: nil, hints: nil)?.width
    #endif
  }
}
