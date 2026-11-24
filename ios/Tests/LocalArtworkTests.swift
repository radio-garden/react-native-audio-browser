import Testing

@testable import AudioBrowserTestable

struct LocalArtworkTests {
  @Test func readsTheArtworkTheAppDrawsItself() {
    #expect(LocalArtwork("sf:star.fill?fg=#fff") == .sfSymbol)
    #expect(LocalArtwork("asset:TabHome") == .asset(name: "TabHome"))
    #expect(LocalArtwork("asset:") == .asset(name: ""))
  }

  @Test func anImageURLIsNotLocal() {
    #expect(LocalArtwork("https://example.com/asset:a.png") == nil)
    #expect(LocalArtwork("https://example.com/sf:a.png") == nil)
    #expect(LocalArtwork(nil) == nil)
  }
}
