@testable import AudioBrowserTestable
import Foundation
import Testing

/// Helper to build a player with mocks for testing.
@MainActor
private func makeCoordinator() -> (
  player: Player,
  engine: MockPlayerEngine,
  callbacks: MockPlayerCallbacks,
  sleepTimer: SleepTimerManager,
) {
  let sleepTimer = SleepTimerManager(scheduler: ManualSleepTimerScheduler())
  let engine = MockPlayerEngine()
  let player = makePlayer(engine: engine, retries: false, sleepTimer: sleepTimer)
  let callbacks = MockPlayerCallbacks()
  player.callbacks = callbacks
  return (player, engine, callbacks, sleepTimer)
}

/// Helper to load a track into the player's queue so it has a currentTrack.
@MainActor
private func loadTrack(
  _ player: Player,
  id: String = "t1",
  src: String? = "https://example.com/audio.mp3",
  title: String = "Test Track",
) {
  let track = Track(id: id, src: src, title: title)
  player.setQueue([track])
}

// MARK: - Transition + Side Effects

@Suite("Player - transition + side effects")
struct TransitionTests {
  @Test @MainActor
  func toReady_startsPlayback_whenPlayWhenReady() {
    let (c, eh, _, _) = makeCoordinator()
    loadTrack(c)
    c.setPlayWhenReady(true)
    eh.startPlaybackCallCount = 0 // reset from playWhenReady setter

    c.transition(.bufferingSufficient)

    #expect(c.state == .ready)
    #expect(eh.startPlaybackCallCount == 1)
  }

  @Test @MainActor
  func toReady_doesNotStart_whenNotPlayWhenReady() {
    let (c, eh, _, _) = makeCoordinator()
    loadTrack(c)
    c.transition(.bufferingSufficient)

    #expect(c.state == .ready)
    #expect(eh.startPlaybackCallCount == 0)
  }

  @Test @MainActor
  func toLoading_setsTimePitchAlgorithm() {
    let (c, eh, _, _) = makeCoordinator()

    c.transition(.trackLoading)

    #expect(c.state == .loading)
    #expect(eh.setTimePitchCallCount == 1)
  }

  @Test @MainActor
  func toError_setsPlaybackError() {
    let (c, _, _, _) = makeCoordinator()

    c.transition(.errorOccurred(.playbackFailed))

    #expect(c.state == .error)
    #expect(c.errorStatus.error == .playbackFailed)
  }

  @Test @MainActor
  func leavingError_clearsPlaybackError() {
    let (c, _, _, _) = makeCoordinator()
    c.transition(.errorOccurred(.playbackFailed))
    #expect(c.errorStatus.error == .playbackFailed)

    c.transition(.trackLoading)

    #expect(c.state == .loading)
    #expect(c.errorStatus.error == nil)
  }

  @Test @MainActor
  func errorToError_updatesAndEmits() {
    let (c, _, cb, _) = makeCoordinator()
    c.transition(.errorOccurred(.playbackFailed))
    cb.playbackChanges.removeAll()
    cb.errorEvents.removeAll()

    c.transition(.errorOccurred(.notConnectedToInternet))

    // State stays .error but error is updated
    #expect(c.state == .error)
    #expect(c.errorStatus.error == .notConnectedToInternet)
    #expect(cb.playbackChanges.count == 1)
    #expect(cb.errorEvents.count == 1)
  }
}

// MARK: - Retrying Errors

/// Advisory errors from the retry loop: attached to the current non-terminal
/// state via `playerDidChangePlayback`, never a state transition and never a
/// `playerDidError`.
/// Builds a player whose retries reload at once, so the advisory path is what shows.
@MainActor
private func makeRetrying() -> (
  Player, RetryLoop, MockPlayerCallbacks,
  MockPlayerEngine,
) {
  let retryHandler = makeRetryLoop()
  let engine = MockPlayerEngine()
  let player = makePlayer(engine: engine, retry: retryHandler)
  let callbacks = MockPlayerCallbacks()
  player.callbacks = callbacks
  return (player, retryHandler, callbacks, engine)
}

@Suite("Player - retrying errors")
struct RetryingErrorTests {
  @Test @MainActor
  func retryingError_attachesToCurrentState() async {
    let (c, _, cb, _) = makeRetrying()
    c.transition(.trackLoading)
    cb.playbackChanges.removeAll()

    c.retry.handleError(URLError(.cannotConnectToHost), context: .mediaLoad)

    #expect(c.state == .loading)
    #expect(c.errorStatus.error == .hostUnreachable)
    #expect(cb.playbackChanges.count == 1)
    #expect(cb.playbackChanges.last?.state == .loading)
    #expect(cb.playbackChanges.last?.error?.retrying == true)
    // Advisory, not terminal: the error event is reserved for .error.
    #expect(cb.errorEvents.isEmpty)
    await c.retry.pendingTask?.value
  }

  /// Every failed attempt re-reports through `handleError`; identical repeats
  /// must not re-emit (they'd re-render now-playing every backoff tick).
  @Test @MainActor
  func repeatedIdenticalFailure_emitsOnce() async {
    let (c, _, cb, _) = makeRetrying()
    c.transition(.trackLoading)
    cb.playbackChanges.removeAll()

    c.retry.handleError(URLError(.cannotConnectToHost), context: .mediaLoad)
    await c.retry.pendingTask?.value
    c.retry.handleError(URLError(.cannotConnectToHost), context: .mediaLoad)
    await c.retry.pendingTask?.value

    #expect(cb.playbackChanges.count == 1)
  }

  /// While .playing, buffered audio is still audibly fine — the advisory must
  /// not replace the song line over working sound (matches Android's guard).
  @Test @MainActor
  func retryingErrorWhilePlaying_isSuppressed() async {
    let (c, _, cb, _) = makeRetrying()
    c.transition(.avPlayerPlaying)
    cb.playbackChanges.removeAll()

    c.retry.handleError(URLError(.cannotConnectToHost), context: .playback)

    #expect(c.errorStatus.error == nil)
    #expect(cb.playbackChanges.isEmpty)
    await c.retry.pendingTask?.value
  }

  /// Reaching .ready proves the retry recovered: the advisory error must not
  /// survive into the clean state's emission.
  @Test @MainActor
  func recovery_clearsTheRetryingError() async {
    let (c, _, cb, _) = makeRetrying()
    c.transition(.trackLoading)
    c.retry.handleError(URLError(.cannotConnectToHost), context: .mediaLoad)
    await c.retry.pendingTask?.value

    c.transition(.loadSeekCompleted)

    #expect(c.state == .ready)
    #expect(c.errorStatus.error == nil)
    #expect(cb.playbackChanges.last?.error == nil)
  }

  /// Giving up hardens the advisory into a terminal error: same kind, state
  /// .error, `retrying` gone.
  @Test @MainActor
  func exhaustion_hardensIntoTerminalError() async {
    let (c, retryHandler, cb, _) = makeRetrying()
    c.transition(.trackLoading)
    c.retry.handleError(URLError(.cannotConnectToHost), context: .mediaLoad)
    await c.retry.pendingTask?.value

    retryHandler.shouldRetry = { false }
    c.retry.handleError(URLError(.cannotConnectToHost), context: .mediaLoad)
    await c.retry.pendingTask?.value

    #expect(c.state == .error)
    #expect(cb.playbackChanges.last?.state == .error)
    #expect(cb.playbackChanges.last?.error?.retrying == nil)
    #expect(cb.errorEvents.count == 1)
  }
}

// MARK: - emitStateChange

@Suite("Player - emitStateChange")
struct EmitStateChangeTests {
  @Test @MainActor
  func alwaysEmits_playerDidChangePlayback() {
    let (c, _, cb, _) = makeCoordinator()

    c.transition(.trackLoading)

    #expect(cb.playbackChanges.count == 1)
    #expect(cb.playbackChanges.first?.state == .loading)
  }

  @Test @MainActor
  func emitsErrorCallback_onEnterError() {
    let (c, _, cb, _) = makeCoordinator()

    c.transition(.errorOccurred(.playbackFailed))

    #expect(cb.errorEvents.count == 1)
  }

  @Test @MainActor
  func emitsErrorCallback_onLeaveError() {
    let (c, _, cb, _) = makeCoordinator()
    c.transition(.errorOccurred(.playbackFailed))
    cb.errorEvents.removeAll()

    c.transition(.trackLoading)

    #expect(cb.errorEvents.count == 1)
    #expect(cb.errorEvents.first?.error == nil) // error cleared
  }

  @Test @MainActor
  func emitsQueueEnded_onEndedAndLastTrack() {
    let (c, eh, cb, _) = makeCoordinator()
    loadTrack(c)
    eh.currentTime = 42
    cb.queueEndedEvents.removeAll()

    c.transition(.trackEndedNaturally)

    #expect(cb.queueEndedEvents.count == 1)
    #expect(cb.queueEndedEvents.first?.position == 42)
    #expect(cb.queueEndedEvents.first?.track == 0)
  }
}

// MARK: - Observer Guards

@Suite("Player - observer guards")
struct ObserverGuardTests {
  @Test @MainActor
  func timeControlStatus_paused_nearTrackEnd_ignored() {
    let (c, eh, cb, _) = makeCoordinator()
    c.transition(.avPlayerPlaying)
    cb.playbackChanges.removeAll()
    eh.hasLoadedAsset = true
    eh.currentTime = 99.8
    eh.duration = 100

    c.avPlayerDidChangeTimeControlStatus(.paused)

    // State should remain .playing — the pause near track end is ignored
    #expect(c.state == .playing)
  }

  @Test @MainActor
  func timeControlStatus_paused_noAsset_transitionsToNone() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.avPlayerPlaying)
    eh.hasLoadedAsset = false
    eh.currentTime = 0
    eh.duration = 0

    c.avPlayerDidChangeTimeControlStatus(.paused)

    #expect(c.state == .none)
  }

  @Test @MainActor
  func timeControlStatus_paused_withAsset_notPlayWhenReady_transitionsToPaused() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.avPlayerPlaying)
    eh.hasLoadedAsset = true
    eh.currentTime = 10
    eh.duration = 100
    c.setPlayWhenReady(false)

    c.avPlayerDidChangeTimeControlStatus(.paused)

    #expect(c.state == .paused)
  }

  @Test @MainActor
  func timeControlStatus_paused_withAsset_playWhenReady_ignored() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.avPlayerPlaying)
    eh.hasLoadedAsset = true
    eh.currentTime = 10
    eh.duration = 100
    c.setPlayWhenReady(true)

    c.avPlayerDidChangeTimeControlStatus(.paused)

    // playWhenReady is true — pause is ignored (likely buffering/seeking)
    #expect(c.state == .playing)
  }

  @Test @MainActor
  func timeControlStatus_ignored_duringLoading() {
    let (c, _, cb, _) = makeCoordinator()
    c.transition(.trackLoading)
    cb.playbackChanges.removeAll()

    c.avPlayerDidChangeTimeControlStatus(.paused)

    #expect(c.state == .loading)
    #expect(cb.playbackChanges.isEmpty)
  }

  @Test @MainActor
  func waiting_noAsset_ignored() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.avPlayerPlaying)
    eh.hasLoadedAsset = false

    c.avPlayerDidChangeTimeControlStatus(.waitingToPlayAtSpecifiedRate)

    #expect(c.state == .playing) // unchanged
  }

  @Test @MainActor
  func waiting_withAsset_transitionsToBuffering() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.avPlayerPlaying)
    eh.hasLoadedAsset = true

    c.avPlayerDidChangeTimeControlStatus(.waitingToPlayAtSpecifiedRate)

    #expect(c.state == .buffering)
  }

  @Test @MainActor
  func playbackLikelyToKeepUp_false_ignored() {
    let (c, _, cb, _) = makeCoordinator()
    c.transition(.trackLoading)
    cb.playbackChanges.removeAll()

    c.avItemDidUpdatePlaybackLikelyToKeepUp(false)

    #expect(c.state == .loading)
    #expect(cb.playbackChanges.isEmpty)
  }

  @Test @MainActor
  func playbackLikelyToKeepUp_true_transitionsToReady() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.trackLoading)
    eh.hasLoadedAsset = true

    c.avItemDidUpdatePlaybackLikelyToKeepUp(true)

    #expect(c.state == .ready)
  }

  @Test @MainActor
  func audioDidStart_ignored_duringLoading() {
    let (c, _, _, _) = makeCoordinator()
    c.transition(.trackLoading)

    c.audioDidStart()

    #expect(c.state == .loading)
  }

  @Test @MainActor
  func audioDidStart_transitionsToPlaying() {
    let (c, _, _, _) = makeCoordinator()
    c.transition(.bufferingSufficient)

    c.audioDidStart()

    #expect(c.state == .playing)
  }
}

// MARK: - playWhenReady

@Suite("Player - playWhenReady")
struct PlayWhenReadyTests {
  @Test @MainActor
  func true_fromError_triggersReload() {
    let (c, eh, _, _) = makeCoordinator()
    loadTrack(c)
    eh.emit(.itemPrepared(likelyToKeepUp: false))
    c.transition(.errorOccurred(.playbackFailed))
    eh.resumePosition = 42
    let resolvesBefore = eh.resolveAndLoadCalls.count

    c.setPlayWhenReady(true)

    // A restart from .error is a new Load on a new AVPlayer, resuming where it failed.
    #expect(eh.recreateCount == 1)
    #expect(eh.resolveAndLoadCalls.count == resolvesBefore + 1)
    #expect(c.state == .loading)
    #expect(c.deferredSeek.target == 42)
  }

  @Test @MainActor
  func true_fromStopped_triggersReload() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.stopped)
    eh.resumePosition = 42

    c.setPlayWhenReady(true)

    #expect(eh.recreateCount == 0)
    #expect(eh.loadResolvedCount == 1)
    #expect(c.deferredSeek.target == nil) // a stop restarts from the start
  }

  @Test @MainActor
  func true_notLoading_startsPlayback() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.bufferingSufficient) // .ready state

    c.setPlayWhenReady(true)

    #expect(eh.startPlaybackCallCount >= 1)
  }

  @Test @MainActor
  func false_notLoading_pausesPlayback() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.avPlayerPlaying)
    c.setPlayWhenReady(true)
    eh.pausePlaybackCallCount = 0

    c.setPlayWhenReady(false)

    #expect(eh.pausePlaybackCallCount == 1)
  }

  @Test @MainActor
  func noOp_duringLoading() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.trackLoading)
    eh.startPlaybackCallCount = 0
    eh.pausePlaybackCallCount = 0

    c.setPlayWhenReady(true)

    // During loading, should NOT call start/pause
    #expect(eh.startPlaybackCallCount == 0)
    #expect(eh.pausePlaybackCallCount == 0)
  }

  @Test @MainActor
  func emitsCallback_onChange() {
    let (c, _, cb, _) = makeCoordinator()

    c.setPlayWhenReady(true)

    #expect(cb.playWhenReadyChanges.count == 1)
    #expect(cb.playWhenReadyChanges.first == true)
  }
}

// MARK: - Skip availability (next/previous greying)

@Suite("Player - skip availability")
struct SkipAvailabilityCoordinatorTests {
  private static func multiTrack() -> [Track] {
    [
      Track(id: "t1", src: "https://example.com/1.mp3", title: "Track 1"),
      Track(id: "t2", src: "https://example.com/2.mp3", title: "Track 2"),
    ]
  }

  @Test @MainActor
  func singleTrack_disablesBoth() {
    let (c, eh, _, _) = makeCoordinator()
    c.setQueue([Track(id: "t1", src: "https://example.com/1.mp3", title: "Track 1")])

    #expect(eh.updateSkipAvailabilityCalls.last?.canNext == false)
    #expect(eh.updateSkipAvailabilityCalls.last?.canPrevious == false)
  }

  @Test @MainActor
  func firstOfMany_nextOnly() {
    let (c, eh, _, _) = makeCoordinator()
    c.setQueue(Self.multiTrack())

    #expect(eh.updateSkipAvailabilityCalls.last?.canNext == true)
    #expect(eh.updateSkipAvailabilityCalls.last?.canPrevious == false)
  }

  @Test @MainActor
  func lastOfMany_previousOnly() {
    let (c, eh, _, _) = makeCoordinator()
    c.setQueue(Self.multiTrack())
    try? c.skipTo(1)

    #expect(eh.updateSkipAvailabilityCalls.last?.canNext == false)
    #expect(eh.updateSkipAvailabilityCalls.last?.canPrevious == true)
  }

  @Test @MainActor
  func repeatAll_repushesAvailability_atEnd() {
    let (c, eh, _, _) = makeCoordinator()
    c.setQueue(Self.multiTrack())
    try? c.skipTo(1) // last track
    eh.updateSkipAvailabilityCalls.removeAll()

    c.repeatMode = .queue

    // repeat-all wraps, so Next becomes available again at the last track.
    #expect(eh.updateSkipAvailabilityCalls.last?.canNext == true)
    #expect(eh.updateSkipAvailabilityCalls.last?.canPrevious == true)
  }
}

// MARK: - handleCurrentTrackChanged

@Suite("Player - handleCurrentTrackChanged")
struct HandleCurrentTrackChangedTests {
  @Test @MainActor
  func clearsError_beforeTransition() {
    let (c, eh, _, _) = makeCoordinator()
    eh.hasLoadedAsset = true
    c.transition(.errorOccurred(.playbackFailed))
    #expect(c.errorStatus.error != nil)

    loadTrack(c)

    // After loading a track, the error should be cleared
    #expect(c.errorStatus.error == nil)
  }

  @Test @MainActor
  func preservesPlayWhenReady_acrossLoading() {
    let (c, _, _, _) = makeCoordinator()
    c.setPlayWhenReady(true)

    loadTrack(c)

    #expect(c.playWhenReady == true)
    #expect(c.state == .loading)
  }

  @Test @MainActor
  func nilSrc_transitionsToError() {
    let (c, _, cb, _) = makeCoordinator()

    let track = Track(id: "t1", src: nil, title: "No Source")
    c.setQueue([track])

    #expect(c.state == .error)
    #expect(c.errorStatus.error == .invalidSourceUrl("nil"))
    #expect(cb.errorEvents.count >= 1)
  }

  @Test @MainActor
  func emitsActiveTrackChangedEvent() {
    let (c, _, cb, _) = makeCoordinator()

    loadTrack(c)

    #expect(cb.activeTrackChanges.count == 1)
    let event = cb.activeTrackChanges.first!
    #expect(event.lastIndex == nil) // first track, no previous
    #expect(event.index == 0)
    #expect(event.track?.id == "t1")
  }
}

// MARK: - handleTrackDidPlayToEndTime

@Suite("Player - handleTrackDidPlayToEndTime")
struct HandleTrackDidPlayToEndTimeTests {
  @Test @MainActor
  func repeatTrack_replays() {
    let (c, eh, _, _) = makeCoordinator()
    startPlaying(c, eh)
    c.repeatMode = .track
    eh.startPlaybackCallCount = 0

    c.handleTrackDidPlayToEndTime()

    #expect(eh.seekCalls == [0])
    #expect(eh.startPlaybackCallCount == 1)
  }

  /// The end-of-track sleep timer pauses (clears intent) just before this
  /// handler runs — an unguarded replay would resume seconds after the sleep
  /// timer paused. Without intent the track settles in .ended, where play()
  /// reloads (a bare .paused would park at the end, where play() no-ops).
  /// A repeating queue never conceptually ends, so no queue-ended reaches JS.
  @Test @MainActor
  func repeatTrack_withoutIntent_settlesEnded() {
    let (c, eh, cb, _) = makeCoordinator()
    loadTrack(c)
    c.repeatMode = .track
    c.setPlayWhenReady(false)

    c.handleTrackDidPlayToEndTime()

    #expect(cb.queueEndedEvents.isEmpty)
    #expect(eh.seekCalls.isEmpty)
    #expect(c.state == .ended)
  }

  @Test @MainActor
  func lastTrack_noRepeat_transitionsToEnded() {
    let (c, eh, cb, _) = makeCoordinator()
    loadTrack(c)
    c.repeatMode = .off

    c.handleTrackDidPlayToEndTime()

    #expect(c.state == .ended)
    #expect(cb.queueEndedEvents.count >= 1)
  }

  @Test @MainActor
  func midQueue_advancesToNext() {
    let (c, _, cb, _) = makeCoordinator()
    let tracks = [
      Track(id: "t1", src: "https://example.com/1.mp3", title: "Track 1"),
      Track(id: "t2", src: "https://example.com/2.mp3", title: "Track 2"),
    ]
    c.setQueue(tracks)
    cb.activeTrackChanges.removeAll()

    c.handleTrackDidPlayToEndTime()

    #expect(c.currentIndex == 1)
    #expect(cb.activeTrackChanges.count == 1)
    #expect(cb.activeTrackChanges.first?.track?.id == "t2")
  }
}

// MARK: - Natural end drops play intent

/// A natural queue end exhausts the play intent: nothing is left to play, so
/// `playWhenReady` must not stay true. Keeping it inverted the play/pause
/// toggle, held the audio session forever, armed interruption auto-resume into
/// silence, and made play-from-ended a silent no-op.
@Suite("Player - natural end drops play intent")
struct NaturalEndPlayIntentTests {
  @Test @MainActor
  func naturalEnd_clearsPlayWhenReady() {
    let (c, eh, _, _) = makeCoordinator()
    startPlaying(c, eh)

    c.handleTrackDidPlayToEndTime()

    #expect(c.state == .ended)
    #expect(c.playWhenReady == false)
  }

  @Test @MainActor
  func naturalEnd_requestsSessionRelease() {
    let (c, eh, callbacks, _) = makeCoordinator()
    startPlaying(c, eh)
    callbacks.releaseSessionCount = 0

    c.handleTrackDidPlayToEndTime()

    #expect(callbacks.releaseSessionCount == 1)
  }

  /// play() from .ended must reload from the start — startPlayback() alone is a
  /// silent no-op on a player parked at the end of its item.
  @Test @MainActor
  func playAfterNaturalEnd_reloadsFromStart() {
    let (c, eh, _, _) = makeCoordinator()
    startPlaying(c, eh)
    c.handleTrackDidPlayToEndTime()
    eh.resumePosition = 300
    let resolvesBefore = eh.resolveAndLoadCalls.count

    c.play()

    #expect(eh.resolveAndLoadCalls.count == resolvesBefore + 1)
    #expect(c.deferredSeek.target == nil)
  }

  /// The cleared intent opens the call-site `!playWhenReady` gate, so a stray
  /// timeControlStatus pause after the end reaches the state machine — the
  /// table's .ended guard must hold the state.
  @Test @MainActor
  func strayPauseAfterNaturalEnd_staysEnded() {
    let (c, eh, _, _) = makeCoordinator()
    startPlaying(c, eh)
    c.handleTrackDidPlayToEndTime()
    #expect(c.state == .ended)

    c.avPlayerDidChangeTimeControlStatus(.paused)

    #expect(c.state == .ended)
  }

  @Test @MainActor
  func interruptionAfterNaturalEnd_doesNotArmResume() {
    let (c, eh, _, _) = makeCoordinator()
    startPlaying(c, eh)
    c.handleTrackDidPlayToEndTime()
    let resolvesBefore = eh.resolveAndLoadCalls.count

    c.handleInterruptionBegan()
    c.handleInterruptionEnded(shouldResume: true)

    #expect(c.playWhenReady == false) // nothing was playing — never resume
    #expect(eh.resolveAndLoadCalls.count == resolvesBefore)
  }
}

// MARK: - Healthy playback refills the retry budget

/// Sustained audible playback proves the stream recovered, so the retry
/// window/attempt counters must refill (mirrors Android's healthy-playback
/// refill) — otherwise a long-lived stream permanently loses retry after its
/// first recovered blip.
@Suite("Player - healthy playback resets retry budget")
struct HealthyPlaybackRetryResetTests {
  /// A player whose retry loop has already executed one retry, so a reset shows.
  @MainActor
  private func makeAfterOneRetry(delay: TimeInterval = 0) async -> (Player, RetryLoop, MockPlayerEngine) {
    let retry = makeRetryLoop(delay: delay)
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine, retry: retry)
    if delay == 0 {
      retry.handleError(URLError(.timedOut), context: .playback)
      await retry.pendingTask?.value
    }
    return (player, retry, engine)
  }

  @Test @MainActor
  func sustainedPlayback_resetsRetryBudget() async {
    let (player, retry, _) = await makeAfterOneRetry()
    #expect(retry.attempts == 1)
    player.healthyPlaybackDuration = 0.01

    player.transition(.avPlayerPlaying)
    await player.healthyPlaybackTask?.value

    #expect(retry.attempts == 0)
  }

  /// reset() cancels a pending retry — firing the refill mid-episode
  /// would kill an in-flight reconnect silently (stuck .playing forever).
  @Test @MainActor
  func refill_skipsWhileRetryIsPending() async {
    let (player, retry, _) = await makeAfterOneRetry(delay: 1)
    player.healthyPlaybackDuration = 0.01

    player.transition(.avPlayerPlaying)
    retry.handleError(URLError(.timedOut), context: .playback)
    await player.healthyPlaybackTask?.value

    #expect(retry.pendingTask != nil)
    retry.cancel()
  }

  @Test @MainActor
  func playbackInterruptedBeforeThreshold_doesNotReset() async {
    let (player, retry, engine) = await makeAfterOneRetry()
    engine.hasLoadedAsset = true
    player.healthyPlaybackDuration = 0.5

    player.transition(.avPlayerPlaying)
    let refill = player.healthyPlaybackTask
    player.transition(.avPlayerWaiting) // stalls before the threshold

    // Leaving .playing cancels the refill; awaiting the cancelled task is the
    // deterministic way to reach the point where it would have fired.
    await refill?.value

    #expect(retry.attempts == 1)
  }
}

// MARK: - Sleep Timer Fade

@Suite("Player - sleep timer fade")
struct SleepTimerFadeTests {
  @Test @MainActor
  func fadeStart_capturesPreFadeVolume() {
    let (c, eh, _, st) = makeCoordinator()
    eh.volume = 0.8

    st.onFadeStart?(10)

    #expect(c.volumeFader.isActive)
    #expect(c.volumeFader.originalVolume == 0.8)
  }

  @Test @MainActor
  func completion_pausesThenRestoresPreFadeVolume() {
    let (c, eh, _, st) = makeCoordinator()
    loadTrack(c)
    c.setPlayWhenReady(true)
    c.transition(.bufferingSufficient) // leave .loading so pause reaches the player
    eh.pausePlaybackCallCount = 0

    st.onFadeStart?(10)
    eh.volume = 0.2 // mid-fade

    st.onComplete?()

    #expect(c.playWhenReady == false)
    #expect(eh.pausePlaybackCallCount == 1)
    #expect(eh.volume == 1.0)
    #expect(!c.volumeFader.isActive)
  }

  @Test @MainActor
  func completion_withoutFade_justPauses() {
    let (c, eh, _, st) = makeCoordinator()
    loadTrack(c)
    c.setPlayWhenReady(true)
    eh.volume = 0.8

    st.onComplete?()

    #expect(c.playWhenReady == false)
    #expect(eh.volume == 0.8) // untouched — no fade ran
  }

  @Test @MainActor
  func fadeCancel_restoresPreFadeVolume() {
    let (c, eh, _, st) = makeCoordinator()
    eh.volume = 0.8

    st.onFadeStart?(10)
    eh.volume = 0.1 // mid-fade

    st.onFadeCancel?()

    #expect(eh.volume == 0.8)
    #expect(!c.volumeFader.isActive)
  }

  @Test @MainActor
  func pauseDuringFade_clearsSleepTimer() {
    let (c, _, _, st) = makeCoordinator()
    loadTrack(c)
    c.setPlayWhenReady(true)
    st.set(seconds: 60, fadeDuration: 10)

    st.onFadeStart?(10)
    c.pause()

    #expect(st.get() == nil)
  }

  @Test @MainActor
  func pauseWithoutFade_leavesSleepTimerAlone() {
    let (c, _, _, st) = makeCoordinator()
    loadTrack(c)
    c.setPlayWhenReady(true)
    st.set(seconds: 60, fadeDuration: 10)

    c.pause()

    #expect(st.get() != nil)
  }
}

// MARK: - Audio Session Interruptions

/// Helper to drive the player into an actively-playing state.
@MainActor
private func startPlaying(_ c: Player, _ eh: MockPlayerEngine) {
  loadTrack(c)
  eh.hasLoadedAsset = true
  eh.hasItem = true
  c.setPlayWhenReady(true)
  c.transition(.avPlayerPlaying)
}

@Suite("Player - audio session interruptions")
struct InterruptionTests {
  @Test @MainActor
  func began_whilePlaying_pauses() {
    let (c, eh, _, _) = makeCoordinator()
    startPlaying(c, eh)
    #expect(c.state == .playing)

    c.handleInterruptionBegan()

    #expect(c.state == .paused)
    #expect(c.playWhenReady == false)
  }

  /// The bug: iOS pauses the AVPlayer first; that `timeControlStatus → paused`
  /// is swallowed because `playWhenReady` is still true, so the state stays
  /// `.playing`. The interruption handler must force the pause regardless.
  @Test @MainActor
  func began_afterSwallowedTimeControlPause_stillPauses() {
    let (c, eh, _, _) = makeCoordinator()
    startPlaying(c, eh)

    c.avPlayerDidChangeTimeControlStatus(.paused)
    #expect(c.state == .playing) // swallowed — the symptom this fix targets

    c.handleInterruptionBegan()

    #expect(c.state == .paused)
  }

  @Test @MainActor
  func ended_shouldResume_resumesWhenWasPlaying() {
    let (c, eh, _, _) = makeCoordinator()
    startPlaying(c, eh)
    c.handleInterruptionBegan()
    #expect(c.playWhenReady == false)
    eh.startPlaybackCallCount = 0

    c.handleInterruptionEnded(shouldResume: true)

    #expect(c.playWhenReady == true)
    #expect(eh.startPlaybackCallCount == 1)
  }

  @Test @MainActor
  func ended_withoutShouldResume_staysPaused() {
    let (c, eh, _, _) = makeCoordinator()
    startPlaying(c, eh)
    c.handleInterruptionBegan()

    c.handleInterruptionEnded(shouldResume: false)

    #expect(c.state == .paused)
    #expect(c.playWhenReady == false)
  }

  /// The host gates audio-session reactivation at interruption-end on this:
  /// paused-when-interrupted must not grab a non-mixable session for a resume
  /// that never happens.
  @Test @MainActor
  func willResumeAfterInterruption_reflectsCapturedIntent() {
    let (c, eh, _, _) = makeCoordinator()
    startPlaying(c, eh)

    c.handleInterruptionBegan()
    #expect(c.willResumeAfterInterruption == true)

    c.handleInterruptionEnded(shouldResume: false)
    #expect(c.willResumeAfterInterruption == false)

    // Paused before the interruption: no resume will happen.
    c.handleInterruptionBegan()
    #expect(c.willResumeAfterInterruption == false)
  }

  /// iOS can deliver nested interruptions (Siri, then a phone call) — a second
  /// .began must not re-capture the now-false intent and wipe the resume flag.
  @Test @MainActor
  func doubleBegan_preservesResumeIntent() {
    let (c, eh, _, _) = makeCoordinator()
    startPlaying(c, eh)

    c.handleInterruptionBegan()
    c.handleInterruptionBegan()
    c.handleInterruptionEnded(shouldResume: true)

    #expect(c.playWhenReady == true)
  }

  /// Unplugging headphones during a call is a deliberate output loss — the
  /// call ending must not blast playback from the built-in speaker.
  @Test @MainActor
  func routeDisconnectDuringInterruption_cancelsResume() {
    let (c, eh, _, _) = makeCoordinator()
    startPlaying(c, eh)
    c.handleInterruptionBegan()

    c.handleRouteDisconnected()
    c.handleInterruptionEnded(shouldResume: true)

    #expect(c.playWhenReady == false)
  }

  /// An explicit stop during the interruption is a terminal command — the
  /// interruption-end must not restart playback over it.
  @Test @MainActor
  func stopDuringInterruption_isNotOverriddenByResume() {
    let (c, eh, _, _) = makeCoordinator()
    startPlaying(c, eh)
    c.handleInterruptionBegan()
    let resolvesBefore = eh.resolveAndLoadCalls.count

    c.stop()
    c.handleInterruptionEnded(shouldResume: true)

    #expect(c.state == .stopped)
    #expect(c.playWhenReady == false)
    #expect(eh.resolveAndLoadCalls.count == resolvesBefore)
  }

  /// A user who plays then pauses during the interruption has taken ownership
  /// of the intent — interruption-end must not override their pause.
  @Test @MainActor
  func userPauseDuringInterruption_overridesResume() {
    let (c, eh, _, _) = makeCoordinator()
    startPlaying(c, eh)
    c.handleInterruptionBegan()

    c.play()
    c.pause()
    c.handleInterruptionEnded(shouldResume: true)

    #expect(c.playWhenReady == false)
  }

  @Test @MainActor
  func ended_doesNotResume_whenNotPlayingBeforeInterruption() {
    let (c, eh, _, _) = makeCoordinator()
    startPlaying(c, eh)
    // Pause before the interruption (e.g. user already paused).
    c.setPlayWhenReady(false)
    c.avPlayerDidChangeTimeControlStatus(.paused)
    #expect(c.state == .paused)

    c.handleInterruptionBegan()
    c.handleInterruptionEnded(shouldResume: true)

    #expect(c.playWhenReady == false) // never resumes — we weren't playing
  }
}

// MARK: - Skip availability freshness

/// A mutation pushes next/previous availability once, after currentIndex and the
/// shuffle order have settled; an earlier push leaves the lock-screen buttons wrong.
@Suite("Player - skip availability after mutations")
struct MutationSkipAvailabilityTests {
  @Test @MainActor
  func move_pushesPostMutationAvailability() throws {
    let (c, eh, _, _) = makeCoordinator()
    c.setQueue([
      Track(id: "a", src: "https://example.com/a.mp3", title: "A"),
      Track(id: "b", src: "https://example.com/b.mp3", title: "B"),
    ])
    eh.updateSkipAvailabilityCalls.removeAll()

    try c.move(fromIndex: 0, toIndex: 1)

    // Current track is now last: nothing to skip to.
    #expect(eh.updateSkipAvailabilityCalls.count == 1)
    #expect(eh.updateSkipAvailabilityCalls.last?.canNext == false)
  }

  @Test @MainActor
  func removeBeforeCurrent_pushesPostMutationAvailability() throws {
    let (c, eh, _, _) = makeCoordinator()
    c.setQueue(
      [
        Track(id: "a", src: "https://example.com/a.mp3", title: "A"),
        Track(id: "b", src: "https://example.com/b.mp3", title: "B"),
        Track(id: "c", src: "https://example.com/c.mp3", title: "C"),
      ], initialIndex: 2,
    )
    eh.updateSkipAvailabilityCalls.removeAll()

    try c.remove(0)

    // Current settled at index 1 of 2: previous available, next not.
    #expect(eh.updateSkipAvailabilityCalls.count == 1)
    #expect(eh.updateSkipAvailabilityCalls.last?.canNext == false)
    #expect(eh.updateSkipAvailabilityCalls.last?.canPrevious == true)
  }
}

// MARK: - Publishing

@Suite("Player - publishing")
struct PublishingTests {
  /// The order JS has always seen: the intent, then the playing state, then the playback. A
  /// natural end clears the intent on the way in; each change still goes out once.
  @Test @MainActor
  func endingPublishesTheIntentThenThePlayingStateThenThePlayback() {
    let (c, _, cb, _) = makeCoordinator()
    c.setPlayWhenReady(true)
    c.transition(.avPlayerPlaying)
    cb.publishOrder.removeAll()

    c.transition(.trackEndedNaturally)

    #expect(cb.publishOrder == ["playWhenReady", "playingState", "playback"])
  }

  @Test @MainActor
  func aTransitionPublishesThePlayingStateBeforeThePlayback() {
    let (c, _, cb, _) = makeCoordinator()
    c.setPlayWhenReady(true)
    cb.publishOrder.removeAll()

    c.transition(.avPlayerPlaying)

    #expect(cb.publishOrder == ["playingState", "playback"])
  }

  @Test @MainActor
  func aRepeatedTerminalErrorIsReportedAgain() {
    let (c, _, cb, _) = makeCoordinator()
    c.transition(.errorOccurred(.playbackFailed))
    c.transition(.errorOccurred(.playbackFailed))

    #expect(cb.errorEvents.count == 2)
  }
}

// MARK: - Load

@Suite("Player - Load")
struct LoadTests {
  @MainActor
  private func track(_ id: String) -> Track {
    Track(id: id, src: "https://example.com/\(id).mp3", title: id)
  }

  @Test @MainActor
  func aNewTrackStartsAnUnprovenLoad() {
    let (c, _, _, _) = makeCoordinator()
    c.setQueue([track("a"), track("b")])
    let first = c.load
    c.audioDidStart()
    #expect(c.load?.hasPlayed == true)

    c.next()

    #expect(c.load != nil && c.load?.id != first?.id)
    #expect(c.load?.hasPlayed == false)
  }

  /// The healthy-playback refill resets the retry budgets mid-play; the load, and with it the
  /// recovery budget, must survive it.
  @Test @MainActor
  func aRetryResetKeepsTheLoad() {
    let (c, _, _, _) = makeCoordinator()
    c.setQueue([track("a")])
    c.audioDidStart()
    let load = c.load

    c.retry.reset()

    #expect(c.load == load)
    #expect(c.load?.hasPlayed == true)
  }

  @Test @MainActor
  func theNowPlayingPrimeRunsOncePerLoad() {
    let (c, _, _, _) = makeCoordinator()
    c.setQueue([track("a")])
    #expect(c.claimNowPlayingPrime())
    #expect(!c.claimNowPlayingPrime())

    c.beginLoad()

    #expect(c.claimNowPlayingPrime())
  }
}

// MARK: - Queue publication

@Suite("Player - queue publication")
struct QueuePublicationTests {
  @MainActor
  private func threeTracks() -> [Track] {
    ["a", "b", "c"].map { Track(id: $0, src: "https://example.com/\($0).mp3", title: $0) }
  }

  @Test @MainActor
  func setQueue_publishesTheQueueBeforeTheActiveTrack() {
    let (c, _, cb, _) = makeCoordinator()
    c.setQueue(threeTracks())
    #expect(cb.queueChanges.map { $0.map(\.id) } == [["a", "b", "c"]])
    #expect(cb.queueAndTrackOrder == ["queue", "activeTrack"])
  }

  @Test @MainActor
  func move_publishesTheSettledQueueOnce() throws {
    let (c, _, cb, _) = makeCoordinator()
    c.setQueue(threeTracks())
    cb.queueChanges.removeAll()
    try c.move(fromIndex: 0, toIndex: 2)
    #expect(cb.queueChanges.map { $0.map(\.id) } == [["b", "c", "a"]])
  }

  @Test @MainActor
  func clear_publishesAnEmptyQueue() {
    let (c, _, cb, _) = makeCoordinator()
    c.setQueue(threeTracks())
    cb.queueChanges.removeAll()
    c.clear()
    #expect(cb.queueChanges.map(\.count) == [0])
  }

  @Test @MainActor
  func navigation_publishesNoQueue() throws {
    let (c, _, cb, _) = makeCoordinator()
    c.setQueue(threeTracks())
    cb.queueChanges.removeAll()
    c.next()
    c.previous()
    try c.skipTo(2)
    #expect(cb.queueChanges.isEmpty)
  }
}

// MARK: - Stop cancels pending retry

@Suite("Player - stop cancels pending retry")
struct StopCancelsRetryTests {
  /// A retry surviving stop() gives up later (intent dropped) and surfaced
  /// .errorOccurred over the deliberate stop — the UI showed an error seconds
  /// after the user stopped.
  @Test @MainActor
  func stop_cancelsPendingRetry_andStaysSilent() async {
    let errorHandler = makeRetryLoop(delay: 0.05)
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine, retry: errorHandler)
    let callbacks = MockPlayerCallbacks()
    player.callbacks = callbacks

    errorHandler.handleError(URLError(.timedOut), context: .playback)
    let task = errorHandler.pendingTask
    #expect(task != nil)

    player.stop()
    #expect(errorHandler.pendingTask == nil)

    await task?.value
    #expect(player.state == .stopped)
    #expect(callbacks.errorEvents.isEmpty)
  }
}

// MARK: - Audio session release (#60)

@Suite("Player - audio session release")
struct SessionReleaseTests {
  @Test @MainActor
  func deliberatePause_requestsRelease() {
    let (c, eh, callbacks, _) = makeCoordinator()
    startPlaying(c, eh)
    callbacks.releaseSessionCount = 0
    c.pause()
    #expect(callbacks.releaseSessionCount == 1)
  }

  @Test @MainActor
  func play_doesNotRequestRelease() {
    let (c, eh, callbacks, _) = makeCoordinator()
    startPlaying(c, eh)
    #expect(callbacks.releaseSessionCount == 0)
  }

  @Test @MainActor
  func interruptionBegan_doesNotRequestRelease() {
    // A phone call pauses (playWhenReady → false) but is meant to resume, so the session must be
    // held — the exact race the intent-gating design exists to avoid.
    let (c, eh, callbacks, _) = makeCoordinator()
    startPlaying(c, eh)
    callbacks.releaseSessionCount = 0
    c.handleInterruptionBegan()
    #expect(callbacks.releaseSessionCount == 0)
  }

  @Test @MainActor
  func interruptionEnded_withResume_doesNotRequestRelease() {
    let (c, eh, callbacks, _) = makeCoordinator()
    startPlaying(c, eh)
    c.handleInterruptionBegan()
    callbacks.releaseSessionCount = 0
    c.handleInterruptionEnded(shouldResume: true)
    #expect(callbacks.releaseSessionCount == 0)
  }

  @Test @MainActor
  func interruptionEnded_withoutResume_requestsRelease() {
    let (c, eh, callbacks, _) = makeCoordinator()
    startPlaying(c, eh)
    c.handleInterruptionBegan()
    callbacks.releaseSessionCount = 0
    c.handleInterruptionEnded(shouldResume: false)
    #expect(callbacks.releaseSessionCount == 1)
  }
}
