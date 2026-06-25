import Testing

@testable import AudioBrowserTestable

/// Pure receiver-status decisions: seek readiness (a property of the playback
/// phase) and the repeat-aware end-of-queue action.
@Suite("CastRemoteStatusInterpreter")
struct CastRemoteStatusInterpreterTests {
  // MARK: - Seek readiness (Phase.allowsSeek)

  @Test func seekAllowedOnlyWhenMediaIsLoaded() {
    #expect(CastRemoteState.Phase.playing.allowsSeek)
    #expect(CastRemoteState.Phase.paused.allowsSeek)
    #expect(CastRemoteState.Phase.buffering.allowsSeek)
  }

  @Test func seekNotAllowedWhileIdleOrLoading() {
    // loading = media not yet loaded → a seek would be dropped; idle = nothing to seek.
    #expect(!CastRemoteState.Phase.idle.allowsSeek)
    #expect(!CastRemoteState.Phase.loading.allowsSeek)
  }

  // MARK: - End-of-queue action (repeat-aware)

  @Test func finishedOnLastItemMapsByRepeatMode() {
    #expect(
      CastRemoteAdvance.endOfQueueAction(
        idleReason: .finished, isLastInPlaybackOrder: true, repeatMode: .track) == .repeatCurrent)
    #expect(
      CastRemoteAdvance.endOfQueueAction(
        idleReason: .finished, isLastInPlaybackOrder: true, repeatMode: .queue) == .advanceNext)
    #expect(
      CastRemoteAdvance.endOfQueueAction(
        idleReason: .finished, isLastInPlaybackOrder: true, repeatMode: .off) == .endNaturally)
  }

  @Test func notLastItemIsNotEndOfQueue() {
    // A mid-queue finish is a normal auto-advance, not end-of-queue.
    #expect(
      CastRemoteAdvance.endOfQueueAction(
        idleReason: .finished, isLastInPlaybackOrder: false, repeatMode: .off)
        == CastEndOfQueueAction.none)
  }

  @Test func nonFinishedIdleIsNotEndOfQueue() {
    for reason in [CastRemoteState.IdleReason.none, .cancelled, .interrupted, .error] {
      #expect(
        CastRemoteAdvance.endOfQueueAction(
          idleReason: reason, isLastInPlaybackOrder: true, repeatMode: .queue)
          == CastEndOfQueueAction.none)
    }
  }
}
