import Testing
@testable import AudioBrowserTestable

/// Helper to build a coordinator with mocks for testing.
@MainActor
private func makeCoordinator() -> (
  coordinator: PlaybackCoordinator,
  effectHandler: MockPlaybackEffectHandler,
  callbacks: MockPlaybackCoordinatorCallbacks,
  sleepTimer: MockSleepTimerHandling
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
  title: String = "Test Track"
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
    eh.startPlaybackCallCount = 0  // reset from playWhenReady setter

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

  @Test @MainActor
  func activeStates_updateNowPlayingValues() {
    let (c, eh, _, _) = makeCoordinator()
    eh.hasLoadedAsset = true
    eh.duration = 120
    eh.currentTime = 30

    c.transition(.trackLoading)
    #expect(eh.updateNowPlayingValuesCalls.count == 1)

    c.transition(.bufferingSufficient)
    #expect(eh.updateNowPlayingValuesCalls.count == 2)
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

    #expect(c.state == .playing)  // unchanged
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

// MARK: - handleTrackDidPlayToEndTime

@Suite("PlaybackCoordinator - handleTrackDidPlayToEndTime")
struct HandleTrackDidPlayToEndTimeTests {
  @Test @MainActor
  func repeatTrack_replays() {
    let (c, eh, _, _) = makeCoordinator()
    loadTrack(c)
    c.repeatMode = .track

    c.handleTrackDidPlayToEndTime()

    // replay delegates to effectHandler.replayCurrentTrack (seek to 0 + play)
    #expect(eh.replayCurrentTrackCallCount == 1)
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
