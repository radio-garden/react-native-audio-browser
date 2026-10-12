import Testing

@testable import AudioBrowserTestable

@Suite("DeferredSeek")
struct DeferredSeekTests {
  @Test func startsIdle() {
    let seek = DeferredSeek()
    #expect(seek.state == .idle)
    #expect(seek.target == nil)
    #expect(!seek.defersReady)
  }

  @Test func aCaptureDefersReadyUntilItLands() {
    var seek = DeferredSeek()
    seek.capture(30)
    #expect(seek.defersReady)
    #expect(seek.begin() == 30)
    #expect(seek.state == .inFlight(30))
    #expect(seek.defersReady)
    #expect(seek.land() == .done)
    #expect(seek.state == .idle)
    #expect(!seek.defersReady)
  }

  @Test func theLatestCaptureWins() {
    var seek = DeferredSeek()
    seek.capture(10)
    seek.capture(20)
    #expect(seek.target == 20)
  }

  @Test func beginHandsOutOnlyAPendingTarget() {
    var seek = DeferredSeek()
    #expect(seek.begin() == nil)
    seek.capture(15)
    #expect(seek.begin() == 15)
    #expect(seek.begin() == nil)
    #expect(seek.target == 15)
  }

  /// A target captured while a seek is in flight is issued when that seek lands, and only its
  /// own landing makes the load ready.
  @Test func aCaptureInFlightIsReissuedOnLanding() {
    var seek = DeferredSeek()
    seek.capture(10)
    _ = seek.begin()
    seek.capture(50)
    #expect(seek.state == .pending(50))
    #expect(seek.land() == .reseek(50))
    #expect(seek.state == .inFlight(50))
    #expect(seek.land() == .done)
  }

  /// A seek issued while playing, not deferred, lands as nothing to do.
  @Test func aLandingWithNothingInFlightIsNone() {
    var seek = DeferredSeek()
    #expect(seek.land() == .none)
  }

  @Test func resetDropsTheTargetAndInvalidatesSeeksInFlight() {
    var seek = DeferredSeek()
    seek.capture(10)
    _ = seek.begin()
    let issuedAgainst = seek.generation
    seek.reset()
    #expect(seek.state == .idle)
    #expect(seek.generation != issuedAgainst)
  }
}
