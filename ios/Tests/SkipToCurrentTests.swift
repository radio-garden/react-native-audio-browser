import Testing

@testable import AudioBrowserTestable

@Suite("Player - a skip to the current item")
struct SkipToCurrentTests {
  private func decide(
    state: PlaybackState = .playing,
    playWhenReady: Bool = true,
    declaredLive: Bool = true,
    positionIsCurrent: Bool = true,
    hasLiveWindow: Bool = false,
    currentTime: Double = 118,
    liveEdge: Double? = 120,
    offsetFromLive: Double? = nil,
  ) -> Player.CurrentItemSkip {
    Player.skipToCurrent(
      state: state,
      playWhenReady: playWhenReady,
      declaredLive: declaredLive,
      positionIsCurrent: positionIsCurrent,
      hasLiveWindow: hasLiveWindow,
      currentTime: currentTime,
      liveEdge: liveEdge,
      offsetFromLive: offsetFromLive,
    )
  }

  @Test func aFailedItemReloads() {
    #expect(decide(state: .error) == .reload)
  }

  @Test func anOnDemandItemStartsOver() {
    // An on-demand item's seekable range is its whole duration.
    #expect(decide(declaredLive: false, currentTime: 60, liveEdge: 300) == .restart)
  }

  @Test func anUndeclaredItemWithoutALiveWindowStartsOver() {
    #expect(decide(declaredLive: false, liveEdge: nil) == .restart)
  }

  @Test func anUndeclaredItemWithALiveWindowAtItsEdgeStays() {
    #expect(decide(declaredLive: false, hasLiveWindow: true) == .stay)
  }

  @Test func anUndeclaredItemStillLoadingStartsOver() {
    #expect(decide(declaredLive: false, positionIsCurrent: false, hasLiveWindow: true) == .restart)
  }

  @Test func aLiveItemStillLoadingJoinsAtItsEdge() {
    #expect(decide(state: .loading, positionIsCurrent: false) == .joinAtEdge)
  }

  @Test func aPlayingLiveItemAtItsEdgeStays() {
    #expect(decide() == .stay)
  }

  @Test func aReadyLiveItemAtItsEdgeStays() {
    #expect(decide(state: .ready) == .stay)
  }

  @Test func aLiveItemWithoutAWindowStays() {
    #expect(decide(currentTime: 0, liveEdge: nil) == .stay)
  }

  @Test func aPausedLiveItemRejoinsItsEdge() {
    #expect(decide(state: .paused, playWhenReady: false) == .rejoin)
  }

  @Test func aLiveItemWithoutAPlayIntentRejoinsItsEdge() {
    #expect(decide(playWhenReady: false) == .rejoin)
  }

  @Test func aBufferingLiveItemRejoinsItsEdge() {
    #expect(decide(state: .buffering) == .rejoin)
  }

  @Test func theToleranceIsMeasuredFromTheLivePosition() {
    #expect(decide(currentTime: 95, offsetFromLive: 25) == .stay)
    #expect(decide(currentTime: 85.01, offsetFromLive: 25) == .stay)
    #expect(decide(currentTime: 85, offsetFromLive: 25) == .rejoin)
  }
}
