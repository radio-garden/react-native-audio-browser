import Testing

@testable import AudioBrowserTestable

@Suite("ArtworkPair")
struct ArtworkPairTests {
  private func pair() -> ArtworkPair<String> {
    ArtworkPair { light, dark in "\(light)+\(dark)" }
  }

  @Test func theFirstHalfToArriveIsShownAlone() {
    var pair = pair()
    #expect(pair.receive(dark: "dark") == "dark")
  }

  @Test func bothHalvesAreShownTogetherOnceTheSecondArrives() {
    var pair = pair()
    _ = pair.receive(light: "light")
    #expect(pair.receive(dark: "dark") == "light+dark")
  }
}
