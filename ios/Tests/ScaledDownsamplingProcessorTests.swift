import CoreGraphics
import Foundation
import Kingfisher
import Testing

@testable import AudioBrowserTestable

@Suite("ScaledDownsamplingProcessor")
struct ScaledDownsamplingProcessorTests {
  private let slot = CGSize(width: 44, height: 44)
  private let cache = ImageCache(name: "scaled-downsampling-\(UUID().uuidString)")
  private let source = Source.provider(PNGProvider(side: 200))

  private func fetched(scale: CGFloat) async throws -> RetrieveImageResult {
    try await KingfisherManager.shared.retrieveImage(
      with: source,
      options: [
        .targetCache(cache),
        .processor(ScaledDownsamplingProcessor(size: slot, scale: scale)),
        .scaleFactor(scale),
        .cacheOriginalImage,
        .waitForCache,
      ],
    )
  }

  private func fetchedPixelWidth(scale: CGFloat) async throws -> Int? {
    try await fetched(scale: scale).image.pixelSize.map { Int($0.width) }
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

  @Test func aSecondFetchAtTheSameScaleIsACacheHit() async throws {
    _ = try await fetched(scale: 2)
    #expect(try await fetched(scale: 2).cacheType == .memory)
  }
}

private struct PNGProvider: ImageDataProvider {
  let side: Int
  var cacheKey: String { "png-\(side)" }

  func data(handler: @escaping @Sendable (Result<Data, any Error>) -> Void) {
    handler(.success(pngData(width: side, height: side)))
  }
}
