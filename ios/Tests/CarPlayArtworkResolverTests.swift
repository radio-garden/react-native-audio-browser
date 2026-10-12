import Testing

@testable import AudioBrowserTestable

@Suite("CarPlayArtworkResolver")
struct CarPlayArtworkResolverTests {
  private func action(
    artwork: String? = nil,
    artworkSource: ArtworkResolvedImage? = nil,
    resolved: ArtworkResolvedImage? = nil,
    stencil: Bool = false,
  ) -> ArtworkLoadAction {
    CarPlayArtworkResolver.action(
      artwork: artwork, artworkSource: artworkSource, resolved: resolved, stencil: stencil, width: 40, height: 40,
    )
  }

  private func image(_ uri: String, headers: [String: String]? = nil) -> ArtworkResolvedImage {
    ArtworkResolvedImage(uri: uri, headers: headers)
  }

  @Test func anSFSymbolRendersAndIsNotResolved() {
    #expect(!CarPlayArtworkResolver.needsResolve(artwork: "sf:star.fill?fg=#fff"))
    #expect(CarPlayArtworkResolver.needsResolve(artwork: "https://example.com/a.jpg"))
    #expect(CarPlayArtworkResolver.needsResolve(artwork: nil))
    #expect(
      action(artwork: "sf:star.fill?fg=#fff", resolved: image("https://example.com/r.jpg"))
        == .sfSymbol(artwork: "sf:star.fill?fg=#fff", width: 40, height: 40),
    )
  }

  @Test func anSFSymbolArtworkSourceRendersAsTheFallback() {
    #expect(action(artworkSource: image("sf:star.fill")) == .sfSymbol(artwork: "sf:star.fill", width: 40, height: 40))
  }

  @Test func nothingToLoadIsNone() {
    #expect(action() == .none)
  }

  @Test func theResolvedURLIsFetchedWithItsHeaders() {
    #expect(
      action(artwork: "https://example.com/a.jpg", resolved: image("https://cdn.example.com/r.jpg", headers: ["A": "1"]))
        == .fetch(uri: "https://cdn.example.com/r.jpg", headers: ["A": "1"], shouldTint: false, isSvg: false),
    )
  }

  @Test func withoutAResolvedURLTheArtworkIsFetched() {
    #expect(
      action(artwork: "https://example.com/a.jpg")
        == .fetch(uri: "https://example.com/a.jpg", headers: nil, shouldTint: false, isSvg: false),
    )
  }

  @Test func anInvalidResolvedURLFallsBackToTheArtwork() {
    #expect(
      action(artwork: "https://example.com/a.jpg", resolved: image(""))
        == .fetch(uri: "https://example.com/a.jpg", headers: nil, shouldTint: false, isSvg: false),
    )
  }

  @Test func withoutArtworkTheArtworkSourceIsFetchedWithItsHeaders() {
    #expect(
      action(artworkSource: image("https://example.com/s.jpg", headers: ["Authorization": "Bearer t"]))
        == .fetch(uri: "https://example.com/s.jpg", headers: ["Authorization": "Bearer t"], shouldTint: false, isSvg: false),
    )
  }

  @Test func artworkWinsOverArtworkSourceWithoutItsHeaders() {
    #expect(
      action(
        artwork: "https://example.com/a.jpg",
        artworkSource: image("https://example.com/s.jpg", headers: ["Authorization": "Bearer t"]),
      ) == .fetch(uri: "https://example.com/a.jpg", headers: nil, shouldTint: false, isSvg: false),
    )
  }

  @Test func anInvalidDirectURLIsNone() {
    #expect(action(artwork: "") == .none)
  }

  @Test func anSVGPathIsMarkedCaseInsensitively() {
    #expect(action(artwork: "https://example.com/i.svg") == .fetch(uri: "https://example.com/i.svg", headers: nil, shouldTint: false, isSvg: true))
    #expect(action(artwork: "https://example.com/i.SVG") == .fetch(uri: "https://example.com/i.SVG", headers: nil, shouldTint: false, isSvg: true))
    #expect(
      action(resolved: image("https://cdn.example.com/i.svg"))
        == .fetch(uri: "https://cdn.example.com/i.svg", headers: nil, shouldTint: false, isSvg: true),
    )
  }

  @Test func aStencilTints() {
    #expect(
      action(artwork: "https://example.com/a.png", stencil: true)
        == .fetch(uri: "https://example.com/a.png", headers: nil, shouldTint: true, isSvg: false),
    )
    #expect(
      action(resolved: image("https://cdn.example.com/r.png"), stencil: true)
        == .fetch(uri: "https://cdn.example.com/r.png", headers: nil, shouldTint: true, isSvg: false),
    )
  }
}
