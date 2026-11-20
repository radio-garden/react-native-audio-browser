import CoreGraphics
import Testing

@testable import AudioBrowserTestable

@Suite("RoutePickerSourceFrame")
struct RoutePickerSourceFrameTests {
  @Test("without an anchor the source is 1x1 at the window's top-left corner")
  func noAnchor() {
    #expect(RoutePickerSourceFrame.inWindow(anchor: nil) == CGRect(x: 0, y: 0, width: 1, height: 1))
  }

  @Test("the source takes the anchor's whole rect")
  func followsAnchor() {
    let anchor = CGRect(x: 16, y: 520, width: 358, height: 44)
    #expect(RoutePickerSourceFrame.inWindow(anchor: anchor) == anchor)
  }

  @Test("an anchor with no area still gives the source 1x1 at its origin")
  func emptyAnchor() {
    let anchor = CGRect(x: 40, y: 300, width: 0, height: 0)
    #expect(RoutePickerSourceFrame.inWindow(anchor: anchor) == CGRect(x: 40, y: 300, width: 1, height: 1))
  }

  @Test("an anchor with a negative size is read as the rect it covers")
  func negativeSize() {
    let anchor = CGRect(x: 100, y: 200, width: -50, height: -20)
    #expect(RoutePickerSourceFrame.inWindow(anchor: anchor) == CGRect(x: 50, y: 180, width: 50, height: 20))
  }
}
