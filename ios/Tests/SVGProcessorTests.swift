import CoreGraphics
import Foundation
import Kingfisher
import Testing

@testable import AudioBrowserTestable

@Suite("SVGProcessor")
struct SVGProcessorTests {
  private let nowPlayingSlot = CGSize(width: 1200, height: 1200)

  private func processed(_ data: Data) -> KFCrossPlatformImage? {
    SVGProcessor(slot: nowPlayingSlot, scale: 1).process(item: .data(data), options: KingfisherParsedOptionsInfo(nil))
  }

  @Test func anSVGRendersToFitTheNowPlayingSlot() throws {
    let svg = Data(#"<svg xmlns="http://www.w3.org/2000/svg" width="100" height="50"><rect width="100" height="50"/></svg>"#.utf8)
    let image = try #require(processed(svg))
    #expect(image.size == CGSize(width: 1200, height: 600))
  }

  @Test func rasterBytesDecodeAsTheyAre() throws {
    let image = try #require(processed(pngData(width: 40, height: 30)))
    #expect(image.pixelSize == CGSize(width: 40, height: 30))
  }
}
