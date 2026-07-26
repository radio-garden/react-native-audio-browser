@testable import AudioBrowserTestable
import Foundation
import Testing

/// Helper to build a coordinator with mocks for testing.
@MainActor
private func makeCoordinator() -> (
  coordinator: PlaybackCoordinator,
  effectHandler: MockPlaybackEffectHandler,
  callbacks: MockPlaybackCoordinatorCallbacks,
  sleepTimer: MockSleepTimerHandling,
) {
  let retryHandler = MockRetryHandling()
  let errorHandler = PlaybackErrorHandler(retryHandler: retryHandler)
  let sleepTimer = MockSleepTimerHandling()
  let coordinator = PlaybackCoordinator(errorHandler: errorHandler, sleepTimerManager: sleepTimer)
  let effectHandler = MockPlaybackEffectHandler()
  let callbacks = MockPlaybackCoordinatorCallbacks()
  coordinator.effectHandler = effectHandler
  coordinator.callbacks = callbacks
  return (coordinator, effectHandler, callbacks, sleepTimer)
}

/// Helper to load a track into the coordinator's queue so it has a currentTrack.
@MainActor
private func loadTrack(
  _ coordinator: PlaybackCoordinator,
  id: String = "t1",
  src: String? = "https://example.com/audio.mp3",
  title: String = "Test Track",
) {
  let track = Track(id: id, src: src, title: title)
  coordinator.setQueue([track])
}

// MARK: - Transition + Side Effects

@Suite("PlaybackCoordinator - transition + side effects")
struct TransitionTests {
  @Test @MainActor
  func toReady_startsPlayback_whenPlayWhenReady() {
    let (c, eh, _, _) = makeCoordinator()
    loadTrack(c)
    c.playWhenReady = true
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
    #expect(c.playbackError == .playbackFailed)
  }

  @Test @MainActor
  func leavingError_clearsPlaybackError() {
    let (c, _, _, _) = makeCoordinator()
    c.transition(.errorOccurred(.playbackFailed))
    #expect(c.playbackError == .playbackFailed)

    c.transition(.trackLoading)

    #expect(c.state == .loading)
    #expect(c.playbackError == nil)
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
    #expect(c.playbackError == .notConnectedToInternet)
    #expect(cb.playbackChanges.count == 1)
    #expect(cb.errorEvents.count == 1)
  }
}

// MARK: - emitStateChange

@Suite("PlaybackCoordinator - emitStateChange")
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

@Suite("PlaybackCoordinator - observer guards")
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
    c.playWhenReady = false

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
    c.playWhenReady = true

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

@Suite("PlaybackCoordinator - playWhenReady")
struct PlayWhenReadyTests {
  @Test @MainActor
  func true_fromError_triggersReload() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.errorOccurred(.playbackFailed))

    c.playWhenReady = true

    #expect(eh.reloadTrackCalls.count == 1)
    #expect(eh.reloadTrackCalls.first == true) // startFromCurrentTime for error
  }

  @Test @MainActor
  func true_fromStopped_triggersReload() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.stopped)

    c.playWhenReady = true

    #expect(eh.reloadTrackCalls.count == 1)
    #expect(eh.reloadTrackCalls.first == false) // startFromCurrentTime for stopped
  }

  @Test @MainActor
  func true_notLoading_startsPlayback() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.bufferingSufficient) // .ready state

    c.playWhenReady = true

    #expect(eh.startPlaybackCallCount >= 1)
  }

  @Test @MainActor
  func false_notLoading_pausesPlayback() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.avPlayerPlaying)
    c.playWhenReady = true
    eh.pausePlaybackCallCount = 0

    c.playWhenReady = false

    #expect(eh.pausePlaybackCallCount == 1)
  }

  @Test @MainActor
  func noOp_duringLoading() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.trackLoading)
    eh.startPlaybackCallCount = 0
    eh.pausePlaybackCallCount = 0

    c.playWhenReady = true

    // During loading, should NOT call start/pause
    #expect(eh.startPlaybackCallCount == 0)
    #expect(eh.pausePlaybackCallCount == 0)
  }

  @Test @MainActor
  func emitsCallback_onChange() {
    let (c, _, cb, _) = makeCoordinator()

    c.playWhenReady = true

    #expect(cb.playWhenReadyChanges.count == 1)
    #expect(cb.playWhenReadyChanges.first == true)
  }
}

// MARK: - Skip availability (next/previous greying)

@Suite("PlaybackCoordinator - skip availability")
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

@Suite("PlaybackCoordinator - handleCurrentTrackChanged")
struct HandleCurrentTrackChangedTests {
  @Test @MainActor
  func clearsError_beforeTransition() {
    let (c, eh, _, _) = makeCoordinator()
    eh.hasLoadedAsset = true
    c.transition(.errorOccurred(.playbackFailed))
    #expect(c.playbackError != nil)

    loadTrack(c)

    // After loading a track, the error should be cleared
    #expect(c.playbackError == nil)
  }

  @Test @MainActor
  func preservesPlayWhenReady_acrossLoading() {
    let (c, _, _, _) = makeCoordinator()
    c.playWhenReady = true

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
    #expect(c.playbackError == .invalidSourceUrl("nil"))
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

@Suite("PlaybackCoordinator - handleTrackDidPlayToEndTime")
struct HandleTrackDidPlayToEndTimeTests {
  @Test @MainActor
  func repeatTrack_replays() {
    let (c, eh, _, _) = makeCoordinator()
    loadTrack(c)
    c.repeatMode = .track
    c.playWhenReady = true

    c.handleTrackDidPlayToEndTime()

    // replay delegates to effectHandler.replayCurrentTrack (seek to 0 + play)
    #expect(eh.replayCurrentTrackCallCount == 1)
  }

  /// The end-of-track sleep timer pauses (clears intent) just before this
  /// handler runs — an unguarded replay would resume seconds after the sleep
  /// timer paused. Without intent the track settles in .ended, where play()
  /// reloads (a bare .paused would park at the end, where play() no-ops).
  @Test @MainActor
  func repeatTrack_withoutIntent_settlesEnded() {
    let (c, eh, _, _) = makeCoordinator()
    loadTrack(c)
    c.repeatMode = .track
    c.playWhenReady = false

    c.handleTrackDidPlayToEndTime()

    #expect(eh.replayCurrentTrackCallCount == 0)
    #expect(c.state == .ended)
  }

  @Test @MainActor
  func lastTrack_noRepeat_transitionsToEnded() {
    let (c, eh, cb, _) = makeCoordinator()
    _ = eh // retain weak effectHandler
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
@Suite("PlaybackCoordinator - natural end drops play intent")
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
    eh.reloadTrackCalls.removeAll()

    c.play()

    #expect(eh.reloadTrackCalls == [false])
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
    eh.reloadTrackCalls.removeAll()

    c.handleInterruptionBegan()
    c.handleInterruptionEnded(shouldResume: true)

    #expect(c.playWhenReady == false) // nothing was playing — never resume
    #expect(eh.reloadTrackCalls.isEmpty)
  }
}

// MARK: - Healthy playback refills the retry budget

/// Sustained audible playback proves the stream recovered, so the retry
/// window/attempt counters must refill (mirrors Android's healthy-playback
/// refill) — otherwise a long-lived stream permanently loses retry after its
/// first recovered blip.
@Suite("PlaybackCoordinator - healthy playback resets retry budget")
struct HealthyPlaybackRetryResetTests {
  @Test @MainActor
  func sustainedPlayback_resetsRetryBudget() async throws {
    let retryHandler = MockRetryHandling()
    let errorHandler = PlaybackErrorHandler(retryHandler: retryHandler)
    let coordinator = PlaybackCoordinator(
      errorHandler: errorHandler, sleepTimerManager: MockSleepTimerHandling(),
    )
    let effectHandler = MockPlaybackEffectHandler()
    coordinator.effectHandler = effectHandler
    coordinator.healthyPlaybackDuration = 0.01

    coordinator.transition(.avPlayerPlaying)
    try await Task.sleep(nanoseconds: 100_000_000)

    #expect(retryHandler.resetCallCount == 1)
  }

  @Test @MainActor
  func playbackInterruptedBeforeThreshold_doesNotReset() async throws {
    let retryHandler = MockRetryHandling()
    let errorHandler = PlaybackErrorHandler(retryHandler: retryHandler)
    let coordinator = PlaybackCoordinator(
      errorHandler: errorHandler, sleepTimerManager: MockSleepTimerHandling(),
    )
    let effectHandler = MockPlaybackEffectHandler()
    coordinator.effectHandler = effectHandler
    effectHandler.hasLoadedAsset = true
    coordinator.healthyPlaybackDuration = 0.5

    coordinator.transition(.avPlayerPlaying)
    coordinator.transition(.avPlayerWaiting) // stalls before the threshold

    try await Task.sleep(nanoseconds: 100_000_000)

    #expect(retryHandler.resetCallCount == 0)
  }
}

@Suite("PlaybackCoordinator - now playing state")
struct PlaybackCoordinatorNowPlayingStateTests {
  @Test @MainActor
  func change_updatesNowPlayingState_evenDuringLoading() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.trackLoading) // mid track-load; start/pause is deferred
    eh.updateNowPlayingStateCalls.removeAll()

    c.playWhenReady = true

    // The lock-screen / CarPlay play-pause button reflects the user's play
    // *intent* immediately, even while the new item is still loading — otherwise
    // it stays stuck showing "paused" until a later state transition repairs it.
    #expect(eh.updateNowPlayingStateCalls.last == true)
  }

  @Test @MainActor
  func staysPlaying_whenBufferRunsDry() {
    let (c, eh, _, _) = makeCoordinator()
    c.playWhenReady = true
    c.transition(.avPlayerPlaying)
    eh.updateNowPlayingStateCalls.removeAll()

    c.transition(.avPlayerWaiting) // buffer ran dry → .buffering

    // A buffer underrun doesn't change play intent, so the button stays "playing"
    // (the system shows buffering) — it must NOT flip to paused like a user pause.
    #expect(eh.updateNowPlayingStateCalls.last == true)
  }

  @Test @MainActor
  func fallsBackToPaused_onError() {
    let (c, eh, _, _) = makeCoordinator()
    c.playWhenReady = true
    c.transition(.trackLoading) // active + intent → playing
    eh.updateNowPlayingStateCalls.removeAll()

    c.transition(.errorOccurred(.playbackFailed))

    // On a terminal error the button must fall back to paused — never stick on
    // "playing" — even though playWhenReady is still true.
    #expect(eh.updateNowPlayingStateCalls.last == false)
  }

  @Test @MainActor
  func change_doesNotStampNowPlayingState_fromTerminalState() {
    let (c, eh, _, _) = makeCoordinator()
    c.transition(.errorOccurred(.playbackFailed)) // terminal, not playbackActive
    eh.updateNowPlayingStateCalls.removeAll()

    c.playWhenReady = true

    // Must NOT stamp .playing from a terminal state: the reload may fail and no
    // active transition would repair a premature "playing", leaving a phantom
    // playing button. The reload's own .loading/.ready transition stamps it.
    #expect(eh.updateNowPlayingStateCalls.isEmpty)
  }

  @Test @MainActor
  func selectingNewTrack_whileStopped_showsPlayingInNowPlaying() {
    let (c, eh, _, _) = makeCoordinator()
    let tracks = [
      Track(id: "t1", src: "https://example.com/1.mp3", title: "Track 1"),
      Track(id: "t2", src: "https://example.com/2.mp3", title: "Track 2"),
    ]
    c.setQueue(tracks) // loads t1 (playWhenReady defaults to false)
    c.transition(.stopped) // user had paused/stopped
    eh.updateNowPlayingStateCalls.removeAll()

    // User selects another track, intending to play it.
    try? c.skipTo(1, playWhenReady: true)

    // Audio will play, so the now-playing button must end up "playing" — not the
    // stale "paused" captured before playWhenReady flipped true during loading.
    #expect(eh.updateNowPlayingStateCalls.last == true)
  }
}

// MARK: - Sleep Timer Fade

@Suite("PlaybackCoordinator - sleep timer fade")
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
    c.playWhenReady = true
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
    c.playWhenReady = true
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
    c.playWhenReady = true

    st.onFadeStart?(10)
    c.pause()

    #expect(st.clearCallCount == 1)
  }

  @Test @MainActor
  func pauseWithoutFade_leavesSleepTimerAlone() {
    let (c, _, _, st) = makeCoordinator()
    loadTrack(c)
    c.playWhenReady = true

    c.pause()

    #expect(st.clearCallCount == 0)
  }
}

// MARK: - Audio Session Interruptions

/// Helper to drive the coordinator into an actively-playing state.
@MainActor
private func startPlaying(_ c: PlaybackCoordinator, _ eh: MockPlaybackEffectHandler) {
  loadTrack(c)
  eh.hasLoadedAsset = true
  c.playWhenReady = true
  c.transition(.avPlayerPlaying)
}

@Suite("PlaybackCoordinator - audio session interruptions")
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
    c.playWhenReady = false
    c.avPlayerDidChangeTimeControlStatus(.paused)
    #expect(c.state == .paused)

    c.handleInterruptionBegan()
    c.handleInterruptionEnded(shouldResume: true)

    #expect(c.playWhenReady == false) // never resumes — we weren't playing
  }
}

// MARK: - Stop cancels pending retry

@Suite("PlaybackCoordinator - stop cancels pending retry")
struct StopCancelsRetryTests {
  /// A retry surviving stop() gives up later (intent dropped) and surfaced
  /// .errorOccurred over the deliberate stop — the UI showed an error seconds
  /// after the user stopped.
  @Test @MainActor
  func stop_cancelsPendingRetry_andStaysSilent() async {
    let retryHandler = MockRetryHandling()
    retryHandler.isRetryableResult = true
    retryHandler.attemptRetryResult = false
    retryHandler.attemptRetryDelayNs = 50_000_000
    let errorHandler = PlaybackErrorHandler(retryHandler: retryHandler)
    let coordinator = PlaybackCoordinator(
      errorHandler: errorHandler, sleepTimerManager: MockSleepTimerHandling(),
    )
    let effectHandler = MockPlaybackEffectHandler()
    let callbacks = MockPlaybackCoordinatorCallbacks()
    coordinator.effectHandler = effectHandler
    coordinator.callbacks = callbacks

    errorHandler.handleError(URLError(.timedOut), context: .playback)
    let task = errorHandler.pendingRetryTask
    #expect(task != nil)

    coordinator.stop()
    #expect(errorHandler.pendingRetryTask == nil)

    await task?.value
    #expect(coordinator.state == .stopped)
    #expect(callbacks.errorEvents.isEmpty)
  }
}

// MARK: - Audio session release (#60)

@Suite("PlaybackCoordinator - audio session release")
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
