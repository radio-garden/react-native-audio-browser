@testable import AudioBrowserTestable
import Foundation
import Testing

/// The decisions the Player makes over its engine: seeking, reloading, recovering, and what an
/// engine event leads to.
@MainActor
private func makeLoading(
  src: String? = "https://example.com/a.mp3",
  live: Bool = false,
) -> (Player, MockPlayerEngine, MockPlayerCallbacks) {
  let engine = MockPlayerEngine()
  let player = makePlayer(engine: engine, retries: false)
  let callbacks = MockPlayerCallbacks()
  player.callbacks = callbacks
  player.setQueue([Track(id: "t1", src: src, title: "T", live: live)])
  return (player, engine, callbacks)
}

/// Loaded and playing: the item is in place and audio has started.
@MainActor
private func makePlaying(live: Bool = false) -> (Player, MockPlayerEngine, MockPlayerCallbacks) {
  let (player, engine, callbacks) = makeLoading(live: live)
  player.setPlayWhenReady(true)
  engine.hasItem = true
  engine.hasLoadedAsset = true
  engine.emit(.itemPrepared(likelyToKeepUp: true))
  player.transition(.avPlayerPlaying)
  return (player, engine, callbacks)
}

@Suite("Player - seeking")
@MainActor
struct PlayerSeekingTests {
  @Test func aSeekWhileLoadingWaitsForTheItem_andHoldsReadyUntilItLands() {
    let (player, engine, _) = makeLoading()
    engine.seekResult = nil
    player.seek(to: 30)
    #expect(engine.seekCalls.isEmpty)

    engine.hasItem = true
    engine.hasLoadedAsset = true
    engine.emit(.itemPrepared(likelyToKeepUp: true))
    #expect(engine.seekCalls == [30])
    #expect(player.state == .loading)
  }

  @Test func theLoadBecomesReadyWhenTheDeferredSeekLands() {
    let (player, engine, _) = makeLoading()
    player.seek(to: 30)
    engine.hasItem = true
    engine.hasLoadedAsset = true
    engine.emit(.itemPrepared(likelyToKeepUp: false))
    #expect(engine.seekCalls == [30])
    #expect(player.state == .ready)
  }

  @Test func seekByWhileLoadingOffsetsTheDeferredTarget() {
    let (player, _, _) = makeLoading()
    player.seek(to: 30)
    player.seek(by: 15)
    #expect(player.deferredSeek.target == 45)
  }

  /// A completion from before a track change belongs to the old item: it reports false and
  /// leaves the new track's start position pending.
  @Test func aCompletionFromBeforeATrackChangeIsDropped() {
    let (player, engine, _) = makePlaying()
    engine.seekResult = nil
    var landed: [Bool] = []
    player.seek(to: 10) { landed.append($0) }

    player.setQueue([Track(id: "t2", src: "https://example.com/b.mp3", title: "B")], startPositionMs: 25000)
    engine.completeSeeks(true)

    #expect(landed == [false])
    #expect(player.deferredSeek.target == 25)
    #expect(player.state == .loading)
  }

  @Test func theLiveEdgeIsTheSeekableWindowsEnd_orAReconnectWithoutOne() {
    let (player, engine, _) = makePlaying(live: true)
    engine.liveEdge = 120
    player.seekToLiveEdge()
    #expect(engine.seekCalls == [120])

    engine.liveEdge = nil
    player.seekToLiveEdge()
    #expect(engine.resolveAndLoadCalls.count == 2)
    #expect(player.state == .loading)
  }
}

@Suite("Player - engine events")
@MainActor
struct PlayerEngineEventTests {
  @Test func aFailedLoadPrimesNowPlayingOncePerLoad() {
    let (player, engine, _) = makeLoading()
    player.setPlayWhenReady(true)
    player.retry.shouldRetry = { true }
    engine.emit(.loadFailed(URLError(.timedOut)))
    engine.emit(.loadFailed(URLError(.timedOut)))
    #expect(engine.primeCount == 1)

    player.setQueue([Track(id: "t2", src: "https://example.com/b.mp3", title: "B")])
    engine.emit(.loadFailed(URLError(.timedOut)))
    #expect(engine.primeCount == 2)
  }

  @Test func aFailedLoadWithoutIntentDoesNotPrime() async {
    let (player, engine, _) = makeLoading()
    engine.emit(.loadFailed(URLError(.timedOut)))
    await player.retry.pendingTask?.value
    #expect(engine.primeCount == 0)
    #expect(player.state == .error)
  }

  @Test func anUnplayableAssetIsTerminal() {
    let (player, engine, _) = makeLoading()
    engine.emit(.unplayable)
    #expect(player.state == .error)
    #expect(player.errorStatus == .terminal(.trackWasUnplayable, report: 1))
  }

  @Test func anInvalidSourceIsTerminal() {
    let (player, engine, _) = makeLoading()
    engine.emit(.invalidSource("::"))
    #expect(player.errorStatus == .terminal(.invalidSourceUrl("::"), report: 1))
  }

  @Test func aPlayerFailureCarriesItsHTTPStatus() {
    let (player, engine, _) = makePlaying()
    engine.emit(.failed(nil, httpStatusCode: 404))
    #expect(player.errorStatus == .terminal(.httpStatus(404), report: 1))
  }

  @Test func aGenuineEndAdvances_aLiveEndReconnects() {
    let (player, engine, _) = makePlaying()
    engine.currentTime = 300
    engine.duration = 300
    engine.emit(.playedToEnd)
    #expect(player.state == .ended)

    let (live, liveEngine, _) = makePlaying(live: true)
    liveEngine.emit(.playedToEnd)
    #expect(live.state == .loading)
    #expect(liveEngine.resolveAndLoadCalls.count == 2)
  }

  @Test func aStallNudgesPlayback_onlyWithIntent() {
    let (player, engine, _) = makePlaying()
    engine.startPlaybackCallCount = 0
    engine.emit(.stalled)
    #expect(engine.startPlaybackCallCount == 1)

    player.pause()
    engine.startPlaybackCallCount = 0
    engine.emit(.stalled)
    #expect(engine.startPlaybackCallCount == 0)
  }

  @Test func anAirPlayStrandingIsNudged() {
    let (player, engine, _) = makePlaying()
    engine.isAirPlayRoute = true
    engine.emit(.timeControlStatus(.waitingToPlayAtSpecifiedRate, waitingForNoItem: true))
    engine.startPlaybackCallCount = 0
    engine.emit(.timeControlStatus(.paused, waitingForNoItem: false))
    #expect(engine.startPlaybackCallCount == 1)
    #expect(player.playWhenReady)
  }
}

@Suite("Player - recovering")
@MainActor
struct PlayerRecoveringTests {
  @Test func theNetworkReturningReconnectsAStalledStream() {
    let (player, engine, _) = makePlaying()
    player.transition(.avPlayerWaiting)
    #expect(player.state == .buffering)
    player.networkDidReturn()
    #expect(engine.resolveAndLoadCalls.count == 2)
  }

  @Test func theNetworkReturningLeavesAPausedOrPlayingStreamAlone() {
    let (player, engine, _) = makePlaying()
    player.networkDidReturn()
    player.pause()
    player.networkDidReturn()
    #expect(engine.resolveAndLoadCalls.count == 1)
  }

  /// A restart from a terminal error is a new Load: fresh retry budgets, unproven playback, the
  /// primer allowed again; a retry within a Load keeps its Load.
  @Test func aRestartFromAnErrorBeginsANewLoad() async {
    let (player, engine, _) = makePlaying()
    engine.emit(.audioDidStart)
    let first = player.load
    #expect(first?.hasPlayed == true)

    engine.emit(.unplayable)
    #expect(player.state == .error)
    player.play()

    #expect(player.load?.id != first?.id)
    #expect(player.load?.hasPlayed == false)
    #expect(player.load?.primedNowPlaying == false)
    #expect(engine.recreateCount == 1)
  }

  @Test func aRetryKeepsItsLoad() async {
    let (player, engine, _) = makePlaying()
    player.retry.shouldRetry = { true }
    let first = player.load
    engine.emit(.failed(URLError(.timedOut), httpStatusCode: nil))
    await player.retry.pendingTask?.value
    #expect(player.load?.id == first?.id)
    #expect(engine.recreateCount == 0)
  }

  @Test func aMediaServicesResetReloadsOnANewPlayer() {
    let (player, engine, _) = makePlaying()
    player.mediaServicesWereReset()
    #expect(engine.recreateCount == 1)
    #expect(engine.resolveAndLoadCalls.count == 2)
    #expect(player.playWhenReady)
  }

  @Test func aStopRewindsAFiniteTrack_notALiveOne() {
    let (player, engine, _) = makePlaying()
    player.stop()
    #expect(engine.seekCalls == [0])

    let (live, liveEngine, _) = makePlaying(live: true)
    live.stop()
    #expect(liveEngine.seekCalls.isEmpty)
  }

  @Test func aNonPositiveRateIsRefused() {
    let (player, engine, _) = makePlaying()
    player.rate = 1.5
    player.rate = 0
    #expect(player.rate == 1.5)
    #expect(engine.rates.map(\.rate) == [1.5])
    #expect(engine.rates.last?.playing == true)
  }

  @Test func isStalledNeedsProgressIntentAndAThinBuffer() {
    let (player, engine, _) = makePlaying()
    engine.currentTime = 10
    engine.isPlaybackLikelyToKeepUp = false
    #expect(player.isStalled)
    engine.currentTime = 0.2
    #expect(!player.isStalled)
  }
}

@Suite("Player - review regressions", .serialized)
@MainActor
struct PlayerReviewRegressionTests {
  /// An end-of-track sleep timer drops the intent while still .playing; the snapshot must still
  /// be rewritten on entering .ended, or a cold resume seeks to the end and re-ends.
  @Test func endingWithTheIntentAlreadyOffSavesPositionZero() {
    UserDefaults(suiteName: "PlayerTests")!.removePersistentDomain(forName: "PlayerTests")
    let (player, engine, _) = makePlaying()
    engine.currentTime = 300
    engine.duration = 300
    player.pause()
    player.transition(.trackEndedNaturally)
    let saved = PlaybackPersistence(defaults: UserDefaults(suiteName: "PlayerTests")!) { nil }.load()
    #expect(saved?.positionMs == 0)
  }

  /// A track change while paused saves the new track at its start, not at the old item's
  /// position, which the engine still reports until the new item is prepared.
  @Test func skippingWhilePausedSavesTheNewTrackAtItsStart() {
    UserDefaults(suiteName: "PlayerTests")!.removePersistentDomain(forName: "PlayerTests")
    let (player, engine, _) = makePlaying()
    player.add([Track(id: "t2", src: "https://example.com/b.mp3", title: "B")])
    player.pause()
    engine.currentTime = 1800
    player.next()
    let saved = PlaybackPersistence(defaults: UserDefaults(suiteName: "PlayerTests")!) { nil }.load()
    #expect(saved?.track.id == "t2")
    #expect(saved?.positionMs == 0)
  }

  /// A reload while paused holds its resume position before the loading transition saves the
  /// snapshot.
  @Test func aRetryWhilePausedSavesWhereItWas() {
    UserDefaults(suiteName: "PlayerTests")!.removePersistentDomain(forName: "PlayerTests")
    let (player, engine, _) = makePlaying()
    player.pause()
    engine.currentTime = 120
    engine.resumePosition = 120
    player.reloadResolving(startFromCurrentTime: true)
    let saved = PlaybackPersistence(defaults: UserDefaults(suiteName: "PlayerTests")!) { nil }.load()
    #expect(saved?.positionMs == 120_000)
    #expect(player.deferredSeek.target == 120)
  }

  /// A skip whose load fails leaves the previous item in the engine: the snapshot and the restart
  /// from the error start the new track at its start, not at the old one's position.
  @Test func aFailedSkipKeepsTheNewTrackAtItsStart() {
    UserDefaults(suiteName: "PlayerTests")!.removePersistentDomain(forName: "PlayerTests")
    let (player, engine, _) = makePlaying()
    player.add([Track(id: "t2", src: "https://example.com/b.mp3", title: "B")])
    engine.currentTime = 1800
    engine.resumePosition = 1800
    player.next()
    engine.emit(.unplayable)
    #expect(player.state == .error)
    let saved = PlaybackPersistence(defaults: UserDefaults(suiteName: "PlayerTests")!) { nil }.load()
    #expect(saved?.track.id == "t2")
    #expect(saved?.positionMs == 0)

    player.play()
    #expect(player.state == .loading)
    #expect(player.deferredSeek.target == nil)
  }

  /// A queue's start position is in the snapshot the track change saves, not only in the next
  /// periodic save.
  @Test func aStartPositionIsSavedWithTheTrackChange() {
    UserDefaults(suiteName: "PlayerTests")!.removePersistentDomain(forName: "PlayerTests")
    let player = makePlayer(retries: false)
    player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "A")], startPositionMs: 30000)
    let saved = PlaybackPersistence(defaults: UserDefaults(suiteName: "PlayerTests")!) { nil }.load()
    #expect(saved?.positionMs == 30000)
    #expect(player.deferredSeek.target == 30)
  }

  /// Play on a track without a source fails as an invalid source; it never reloads the media
  /// loader's last URL, which belongs to an earlier track.
  @Test func playOnATrackWithoutASourceFailsInsteadOfReloading() {
    let (player, engine, _) = makePlaying()
    player.setQueue([Track(id: "t2", src: nil, title: "No source")])
    #expect(player.state == .error)
    let resolves = engine.resolveAndLoadCalls.count

    player.play()

    #expect(player.state == .error)
    #expect(player.nitroPlaybackError?.code == TrackPlayerError.PlaybackError.invalidSourceUrl("nil").code)
    #expect(engine.resolveAndLoadCalls.count == resolves)
  }

  /// While nothing plays, a transition alone rewrites the snapshot.
  @Test func anErrorWhilePausedSavesWhereItStopped() {
    UserDefaults(suiteName: "PlayerTests")!.removePersistentDomain(forName: "PlayerTests")
    let (player, engine, _) = makePlaying()
    player.pause()
    engine.currentTime = 42
    engine.emit(.failed(nil, httpStatusCode: nil))
    #expect(player.state == .error)
    let saved = PlaybackPersistence(defaults: UserDefaults(suiteName: "PlayerTests")!) { nil }.load()
    #expect(saved?.positionMs == 42000)
  }

  /// An active play intent saves the snapshot periodically.
  @Test func playingSavesPeriodically() async throws {
    UserDefaults(suiteName: "PlayerTests")!.removePersistentDomain(forName: "PlayerTests")
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine, retries: false, saveInterval: .milliseconds(10))
    player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")], playWhenReady: true)
    engine.hasItem = true
    engine.hasLoadedAsset = true
    engine.emit(.itemPrepared(likelyToKeepUp: true))
    player.transition(.avPlayerPlaying)
    engine.currentTime = 7
    let persistence = PlaybackPersistence(defaults: UserDefaults(suiteName: "PlayerTests")!) { nil }
    for _ in 0 ..< 100 where persistence.load()?.positionMs != 7000 {
      try await Task.sleep(for: .milliseconds(10))
    }
    #expect(persistence.load()?.positionMs == 7000)
    player.pause()
  }

  /// A persisted session resumes its settings, then its track at the saved position.
  @Test func aPersistedSessionResumesWhereItWasSaved() throws {
    let defaults = UserDefaults(suiteName: "PlayerTests")!
    defaults.removePersistentDomain(forName: "PlayerTests")
    let saved = PersistedPlaybackState(
      track: JsonTrack(from: Track(id: "t1", src: "https://example.com/a.mp3", title: "A")),
      positionMs: 30000, repeatMode: "queue", shuffleEnabled: false, playbackSpeed: 1.5,
    )
    PlaybackPersistence(defaults: defaults) { saved }.save()

    let player = makePlayer(retries: false)
    let session = try #require(player.persistedSession())
    player.resume(session, queue: nil)
    #expect(player.currentTrack?.id == "t1")
    #expect(player.deferredSeek.target == 30)
    #expect(player.repeatMode == .queue)
    #expect(player.rate == 1.5)
    #expect(player.playWhenReady)
  }

  @Test func aDisabledPersistedTrackIsNotResumed() {
    let defaults = UserDefaults(suiteName: "PlayerTests")!
    defaults.removePersistentDomain(forName: "PlayerTests")
    let saved = PersistedPlaybackState(
      track: JsonTrack(from: Track(id: "t1", src: "https://example.com/a.mp3", title: "A", disabled: true)),
      positionMs: nil, repeatMode: "off", shuffleEnabled: false, playbackSpeed: 1,
    )
    PlaybackPersistence(defaults: defaults) { saved }.save()
    #expect(makePlayer(retries: false).persistedSession() == nil)
  }

  /// Leaving an errored track publishes the new track's loading state once, never an error
  /// state without its error.
  @Test func skippingAwayFromAnErrorNeverPublishesAnErrorlessError() {
    let (player, engine, callbacks) = makeLoading()
    engine.emit(.unplayable)
    callbacks.playbackChanges.removeAll()
    callbacks.errorEvents.removeAll()
    player.setQueue([Track(id: "t2", src: "https://example.com/b.mp3", title: "B")])
    #expect(!callbacks.playbackChanges.contains { $0.state == .error })
    #expect(callbacks.errorEvents.count == 1)
  }
}
