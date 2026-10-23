@testable import AudioBrowserTestable
import Testing

@Suite("NowPlayingButtonRules")
struct NowPlayingButtonRulesTests {
  @Test func buttons_dropRepeats_keepingFirstOrder() {
    #expect(NowPlayingButtonRules.buttons(["shuffle", "favorite", "shuffle", "repeat"]) == ["shuffle", "favorite", "repeat"])
  }

  @Test func buttons_capAtFive_afterDroppingRepeats() {
    let configured = ["a", "a", "b", "c", "d", "e", "f"]
    #expect(NowPlayingButtonRules.buttons(configured) == ["a", "b", "c", "d", "e"])
  }

  @Test func buttons_empty() {
    #expect(NowPlayingButtonRules.buttons([String]()).isEmpty)
  }

  @Test func repeatMode_cyclesOffTrackQueue() {
    #expect(NowPlayingButtonRules.repeatMode(after: .off) == .track)
    #expect(NowPlayingButtonRules.repeatMode(after: .track) == .queue)
    #expect(NowPlayingButtonRules.repeatMode(after: .queue) == .off)
  }

  @Test func rate_advancesFromAConfiguredRate_andWraps() {
    let rates = [0.5, 1, 1.5, 2]
    #expect(NowPlayingButtonRules.rate(after: 1, in: rates) == 1.5)
    #expect(NowPlayingButtonRules.rate(after: 2, in: rates) == 0.5)
  }

  @Test func rate_matchesAConfiguredRateWithinAHundredth() {
    #expect(NowPlayingButtonRules.rate(after: 1.005, in: [1, 1.5]) == 1.5)
  }

  @Test func rate_offTheList_takesTheFirstAboveIt_elseTheFirst() {
    #expect(NowPlayingButtonRules.rate(after: 1.2, in: [0.5, 1, 1.5]) == 1.5)
    #expect(NowPlayingButtonRules.rate(after: 3, in: [0.5, 1, 1.5]) == 0.5)
  }

  @Test func rate_noRates_isNil() {
    #expect(NowPlayingButtonRules.rate(after: 1, in: []) == nil)
  }
}
