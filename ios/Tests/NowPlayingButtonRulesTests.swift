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

  @Test func queueItemPick_isAllowedUnlessTheCapabilityIsFalse() {
    #expect(NowPlayingButtonRules.queueItemPickAllowed(nil))
    #expect(NowPlayingButtonRules.queueItemPickAllowed(true))
    #expect(!NowPlayingButtonRules.queueItemPickAllowed(false))
  }

  @Test func queueItemPick_skipsOnTheQueueTheRowWasBuiltFor() {
    #expect(NowPlayingButtonRules.queueItemPick(skipToQueueItem: nil, rowQueueVersion: 7, queueVersion: 7) == .skip)
    #expect(NowPlayingButtonRules.queueItemPick(skipToQueueItem: true, rowQueueVersion: 7, queueVersion: 7) == .skip)
  }

  @Test func queueItemPick_afterAQueueChange_rebuildsInsteadOfSkipping() {
    #expect(NowPlayingButtonRules.queueItemPick(skipToQueueItem: nil, rowQueueVersion: 7, queueVersion: 8) == .rebuild)
  }

  @Test func queueItemPick_refusedWithTheCapabilityOffOrNoPlayer() {
    #expect(NowPlayingButtonRules.queueItemPick(skipToQueueItem: false, rowQueueVersion: 7, queueVersion: 7) == .refuse)
    #expect(NowPlayingButtonRules.queueItemPick(skipToQueueItem: false, rowQueueVersion: 7, queueVersion: 8) == .refuse)
    #expect(NowPlayingButtonRules.queueItemPick(skipToQueueItem: nil, rowQueueVersion: 7, queueVersion: nil) == .refuse)
  }

  @Test func upNext_needsATrackAfterTheCurrentOne_withTheCapabilityUnsetOrOn() {
    #expect(NowPlayingButtonRules.upNextEnabled(option: true, skipToQueueItem: nil, upNextCount: 1))
    #expect(NowPlayingButtonRules.upNextEnabled(option: true, skipToQueueItem: true, upNextCount: 1))
    #expect(!NowPlayingButtonRules.upNextEnabled(option: true, skipToQueueItem: nil, upNextCount: 0))
  }

  @Test func upNext_isOffWithoutTheOption() {
    #expect(!NowPlayingButtonRules.upNextEnabled(option: false, skipToQueueItem: true, upNextCount: 1))
  }

  @Test func upNext_isOffWithoutTheCapability_whateverTheOption() {
    #expect(!NowPlayingButtonRules.upNextEnabled(option: true, skipToQueueItem: false, upNextCount: 1))
  }
}
