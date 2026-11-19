import CoreGraphics
import Testing

@testable import AudioBrowserTestable

@Suite("CGSize.aspectFitted(in:)")
struct CGSizeAspectFitTests {
  private let slot = CGSize(width: 40, height: 40)

  @Test func aLargeCanvasShrinksToTheSlot() {
    #expect(CGSize(width: 1024, height: 1024).aspectFitted(in: slot) == slot)
  }

  @Test func aSmallCanvasGrowsToTheSlot() {
    #expect(CGSize(width: 24, height: 24).aspectFitted(in: slot) == slot)
  }

  @Test func aWideCanvasKeepsItsAspectRatio() {
    #expect(CGSize(width: 200, height: 100).aspectFitted(in: slot) == CGSize(width: 40, height: 20))
  }

  @Test func aTallCanvasKeepsItsAspectRatio() {
    #expect(CGSize(width: 100, height: 400).aspectFitted(in: slot) == CGSize(width: 10, height: 40))
  }

  @Test(arguments: [
    CGSize.zero,
    CGSize(width: 100, height: 0),
    CGSize(width: -10, height: 10),
    CGSize(width: CGFloat.infinity, height: 10),
    CGSize(width: CGFloat.nan, height: 10),
  ])
  func aCanvasWithoutAnAspectRatioFillsTheSlot(_ canvas: CGSize) {
    #expect(canvas.aspectFitted(in: slot) == slot)
  }
}
