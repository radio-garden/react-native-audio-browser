import Testing

@testable import AudioBrowserTestable

@Suite("AirPlayStallJudgement")
struct AirPlayStallJudgementTests {
  /// The defaults are the one transition that nudges: over AirPlay, AVPlayer drops
  /// from .waitingToPlayAtSpecifiedRate(.noItemToPlay) to .paused while still meant
  /// to play. Each test below breaks one condition of it.
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
    // An ordinary stall (.toMinimizeStalls etc.) that ends in .paused is not the AirPlay drop.
    #expect(!judge(reasonWasNoItemToPlay: false).shouldNudge)
  }
}
