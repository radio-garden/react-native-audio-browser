import CoreGraphics
import Kingfisher
import Testing

@testable import AudioBrowserTestable

@Suite("SlotCanvasProcessor")
struct SlotCanvasProcessorTests {
  private let slot = CGSize(width: 44, height: 44)

  private func processed(width: Int, height: Int, slot: CGSize? = nil, scale: CGFloat = 2) -> KFCrossPlatformImage? {
    SlotCanvasProcessor(slot: slot ?? self.slot, scale: scale)
      .process(item: .data(pngData(width: width, height: height)), options: KingfisherParsedOptionsInfo([.scaleFactor(scale)]))
  }

  @Test(arguments: [(200, 200), (200, 100), (30, 90), (20, 20)])
  func anyImageLandsOnACanvasOfTheSlotAtTheScale(width: Int, height: Int) throws {
    let image = try #require(processed(width: width, height: height))
    #expect(image.pixelSize == CGSize(width: 88, height: 88))
    #if canImport(UIKit)
      #expect(image.size == slot)
      #expect(image.scale == 2)
    #endif
  }

  @Test func theCanvasFollowsTheScale() throws {
    let image = try #require(processed(width: 200, height: 200, scale: 3))
    #expect(image.pixelSize == CGSize(width: 132, height: 132))
  }

  @Test(arguments: [CGSize.zero, CGSize(width: -44, height: 44)])
  func aSlotWithNoAreaLeavesTheImageAsItIs(slot: CGSize) throws {
    let image = try #require(processed(width: 200, height: 100, slot: slot))
    #expect(image.pixelSize == CGSize(width: 200, height: 100))
  }

  @Test func eachSlotAndScaleIsItsOwnCacheEntry() {
    let identifiers = Set([
      SlotCanvasProcessor(slot: slot, scale: 2).identifier,
      SlotCanvasProcessor(slot: slot, scale: 3).identifier,
      SlotCanvasProcessor(slot: CGSize(width: 30, height: 30), scale: 2).identifier,
    ])
    #expect(identifiers.count == 3)
  }
}
