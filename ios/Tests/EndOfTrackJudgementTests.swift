import Testing

@testable import AudioBrowserTestable

@Suite("EndOfTrackJudgement")
struct EndOfTrackJudgementTests {
  // Live streams are infinite — an end-of-item is always a dropped connection,
  // never a real end, regardless of the reported position/duration.
  @Test func live_isAlwaysStalled() {
    #expect(EndOfTrackJudgement(isLive: true, currentTime: 0, duration: 0).outcome == .stalled)
    #expect(EndOfTrackJudgement(isLive: true, currentTime: 100, duration: 100).outcome == .stalled)
    #expect(EndOfTrackJudgement(isLive: true, currentTime: 5, duration: .nan).outcome == .stalled)
  }

  // On-demand reaching the end is a genuine finish.
  @Test func onDemand_atEnd_isEnded() {
    #expect(EndOfTrackJudgement(isLive: false, currentTime: 300, duration: 300).outcome == .ended)
  }

  // Within the 5% tail still counts as a real finish (decoder rarely lands exactly).
  @Test func onDemand_withinFivePercentOfEnd_isEnded() {
    #expect(EndOfTrackJudgement(isLive: false, currentTime: 291, duration: 300).outcome == .ended)
  }

  // More than 5% short of the end → a mid-stream stall reported as EOF.
  @Test func onDemand_wellShortOfEnd_isStalled() {
    #expect(EndOfTrackJudgement(isLive: false, currentTime: 120, duration: 300).outcome == .stalled)
  }

  // Unknown/indefinite duration can't be validated — trust the notification.
  @Test func onDemand_indefiniteDuration_isEnded() {
    #expect(EndOfTrackJudgement(isLive: false, currentTime: 0, duration: .nan).outcome == .ended)
    #expect(EndOfTrackJudgement(isLive: false, currentTime: 0, duration: 0).outcome == .ended)
  }
}
