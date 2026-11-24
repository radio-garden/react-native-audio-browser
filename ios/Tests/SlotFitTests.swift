import CoreGraphics
import Testing

@testable import AudioBrowserTestable

@Suite("SlotFit.drawRect(for:in:)")
struct SlotFitTests {
  private let slot = CGSize(width: 44, height: 44)

  @Test func anImageSmallerThanTheSlotFillsIt() {
    #expect(SlotFit.drawRect(for: CGSize(width: 30, height: 30), in: slot) == CGRect(origin: .zero, size: slot))
  }

  @Test func anImageLargerThanTheSlotFillsIt() {
    #expect(SlotFit.drawRect(for: CGSize(width: 95, height: 95), in: slot) == CGRect(origin: .zero, size: slot))
  }

  @Test func aWideImageIsCenteredVertically() {
    #expect(SlotFit.drawRect(for: CGSize(width: 88, height: 44), in: slot) == CGRect(x: 0, y: 11, width: 44, height: 22))
  }

  @Test func aTallImageIsCenteredHorizontally() {
    #expect(SlotFit.drawRect(for: CGSize(width: 22, height: 88), in: slot) == CGRect(x: 16.5, y: 0, width: 11, height: 44))
  }

  @Test func anImageWithNoAspectRatioHasNoRect() {
    #expect(SlotFit.drawRect(for: .zero, in: slot) == nil)
  }

  @Test func anImageAlreadyAtTheSlotAndScaleNeedsNoRedraw() {
    #expect(!SlotFit.needsRedraw(imageSize: slot, imageScale: 2, slot: slot, scale: 2))
  }

  @Test func anImageOfAnotherSizeOrScaleNeedsARedraw() {
    #expect(SlotFit.needsRedraw(imageSize: CGSize(width: 30, height: 30), imageScale: 2, slot: slot, scale: 2))
    #expect(SlotFit.needsRedraw(imageSize: slot, imageScale: 3, slot: slot, scale: 2))
  }
}
