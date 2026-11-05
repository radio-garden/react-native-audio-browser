import Testing

@testable import AudioBrowserTestable

@Suite("EndOfTrackJudgement")
struct EndOfTrackJudgementTests {
  @Test func live_isAlwaysStalled() {
    #expect(EndOfTrackJudgement(isLive: true, currentTime: 0, duration: 0).outcome == .stalled)
    #expect(EndOfTrackJudgement(isLive: true, currentTime: 100, duration: 100).outcome == .stalled)
    #expect(EndOfTrackJudgement(isLive: true, currentTime: 5, duration: .nan).outcome == .stalled)
  }

  @Test func onDemand_atEnd_isEnded() {
    #expect(EndOfTrackJudgement(isLive: false, currentTime: 300, duration: 300).outcome == .ended)
  }

  // The position at a genuine end rarely lands exactly on the duration.
  @Test func onDemand_withinFivePercentOfEnd_isEnded() {
    #expect(EndOfTrackJudgement(isLive: false, currentTime: 291, duration: 300).outcome == .ended)
  }

  @Test func onDemand_wellShortOfEnd_isStalled() {
    #expect(EndOfTrackJudgement(isLive: false, currentTime: 120, duration: 300).outcome == .stalled)
  }

  @Test func onDemand_indefiniteDuration_isEnded() {
    #expect(EndOfTrackJudgement(isLive: false, currentTime: 0, duration: .nan).outcome == .ended)
    #expect(EndOfTrackJudgement(isLive: false, currentTime: 0, duration: 0).outcome == .ended)
  }
}
