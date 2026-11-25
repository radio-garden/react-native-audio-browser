import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("ArtworkProcessing")
struct ArtworkProcessingTests {
  private func processing(_ uri: String, onCanvas: Bool) -> ArtworkProcessing {
    ArtworkProcessing(url: URL(string: uri)!, onCanvas: onCanvas)
  }

  @Test func anSVGRasterizesWithOrWithoutACanvas() {
    #expect(processing("https://example.com/i.svg", onCanvas: false) == .svg(onCanvas: false))
    #expect(processing("https://example.com/i.svg", onCanvas: true) == .svg(onCanvas: true))
  }

  @Test func anSVGPathIsRecognisedCaseInsensitivelyAndPastItsQuery() {
    #expect(processing("https://example.com/i.SVG", onCanvas: false) == .svg(onCanvas: false))
    #expect(processing("https://example.com/i.svg?w=40", onCanvas: false) == .svg(onCanvas: false))
  }

  @Test func aRasterImageIsDownsampledOnlyOntoACanvas() {
    #expect(processing("https://example.com/a.png", onCanvas: true) == .downsampledOntoCanvas)
    #expect(processing("https://example.com/a.png", onCanvas: false) == .asFetched)
  }
}
