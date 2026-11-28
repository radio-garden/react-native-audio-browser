import Testing

@testable import AudioBrowserTestable

@Suite("ArtworkPair")
struct ArtworkPairTests {
  private func pair() -> ArtworkPair<String> {
    ArtworkPair { light, dark in "\(light)+\(dark)" }
  }

  @Test func aHalfIsNotShownWhileTheOtherIsLoading() {
    var pair = pair()
    #expect(pair.light("light") == nil)
  }

  @Test func bothHalvesAreShownTogetherOnceBothArrive() {
    var pair = pair()
    _ = pair.light("light")
    #expect(pair.dark("dark") == "light+dark")
  }

  @Test func aHalfIsShownAloneOnceTheOtherFailed() {
    var pair = pair()
    #expect(pair.dark(nil) == nil)
    #expect(pair.light("light") == "light")
  }

  @Test func aHalfThatFailedFirstIsShownAloneWhenTheOtherFails() {
    var pair = pair()
    _ = pair.light("light")
    #expect(pair.dark(nil) == "light")
  }

  @Test func aHalfThatLoadsAfterFailingJoinsTheOther() {
    var pair = pair()
    _ = pair.light("light")
    _ = pair.dark(nil)
    #expect(pair.dark("dark") == "light+dark")
  }
}
