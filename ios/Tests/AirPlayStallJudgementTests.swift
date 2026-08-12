import Testing

@testable import AudioBrowserTestable

@Suite("AirPlayStallJudgement")
struct AirPlayStallJudgementTests {
  /// The exact transition Pocket Casts recovers from: over AirPlay, AVPlayer
  /// drops .waitingToPlayAtSpecifiedRate(.noItemToPlay) → .paused while still
  /// meant to play. That, and only that, should nudge.
  private func judge(
    previous: PlayerTimeControlStatus? = .waitingToPlayAtSpecifiedRate,
    current: PlayerTimeControlStatus = .paused,
    reasonWasNoItemToPlay: Bool = true,
    isAirPlay: Bool = true,
    playWhenReady: Bool = true,
  ) -> AirPlayStallJudgement {
    AirPlayStallJudgement(
      previous: previous,
      current: current,
      previousWaitingReasonWasNoItemToPlay: reasonWasNoItemToPlay,
      isAirPlay: isAirPlay,
      playWhenReady: playWhenReady,
    )
  }

  @Test func exactTransition_nudges() {
    #expect(judge().shouldNudge)
  }

  @Test func notAirPlay_doesNotNudge() {
    #expect(!judge(isAirPlay: false).shouldNudge)
  }

  @Test func notPlayWhenReady_doesNotNudge() {
    #expect(!judge(playWhenReady: false).shouldNudge)
  }

  @Test func previousNotWaiting_doesNotNudge() {
    #expect(!judge(previous: .playing).shouldNudge)
    #expect(!judge(previous: nil).shouldNudge)
  }

  @Test func currentNotPaused_doesNotNudge() {
    #expect(!judge(current: .playing).shouldNudge)
    #expect(!judge(current: .waitingToPlayAtSpecifiedRate).shouldNudge)
  }

  @Test func reasonNotNoItemToPlay_doesNotNudge() {
    // A normal stall (.toMinimizeStalls etc.) landing in paused isn't the bug.
    #expect(!judge(reasonWasNoItemToPlay: false).shouldNudge)
  }
}
