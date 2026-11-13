@testable import AudioBrowserTestable
import Foundation
import Testing

@MainActor
private func makeLoading(
  src: String? = "https://example.com/a.mp3",
  live: Bool = false,
  scheduler: any SleepTimerScheduling = ManualSleepTimerScheduler(),
) throws -> (Player, MockPlayerEngine, MockPlayerCallbacks) {
  let engine = MockPlayerEngine()
  let player = makePlayer(engine: engine, sleepTimerScheduler: scheduler)
  let callbacks = MockPlayerCallbacks()
  player.callbacks = callbacks
  try player.setQueue([Track(id: "t1", src: src, title: "T", live: live)])
  return (player, engine, callbacks)
}

@MainActor
private func makePlaying(
  live: Bool = false,
  scheduler: any SleepTimerScheduling = ManualSleepTimerScheduler(),
) throws -> (Player, MockPlayerEngine, MockPlayerCallbacks) {
  let (player, engine, callbacks) = try makeLoading(live: live, scheduler: scheduler)
  player.setPlayWhenReady(true)
  engine.hasItem = true
  engine.hasLoadedAsset = true
  engine.emit(.itemPrepared(likelyToKeepUp: true))
  player.transition(.avPlayerPlaying)
  return (player, engine, callbacks)
}

/// Playing with retries that follow the play intent, then stalled: the buffer ran dry and
/// AVPlayer waits (.buffering), with a stall timeout short enough to await.
@MainActor
private func makeStalling(givesUp: Bool = false) throws -> (Player, MockPlayerEngine, MockPlayerCallbacks) {
  let engine = MockPlayerEngine()
  let player = makePlayer(engine: engine, retry: makeRetryLoop(givesUp: givesUp))
  player.retry.stallTimeout = .milliseconds(10)
  let callbacks = MockPlayerCallbacks()
  player.callbacks = callbacks
  try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")], playWhenReady: true)
  engine.hasItem = true
  engine.hasLoadedAsset = true
  engine.emit(.itemPrepared(likelyToKeepUp: true))
  player.transition(.avPlayerPlaying)
  engine.emit(.stalled)
  player.transition(.avPlayerWaiting)
  return (player, engine, callbacks)
}

/// A live stream that loaded and waits to play, its retry loop on a virtual clock.
@MainActor
private func makeVirtual(online: Bool = true) throws -> (Player, MockPlayerEngine, MockPlayerCallbacks, VirtualClock) {
  let clock = VirtualClock()
  let retry = RetryLoop(online: online, clock: clock)
  retry.updatePolicy(from: .first(true))
  let engine = MockPlayerEngine()
  let player = makePlayer(engine: engine, retry: retry)
  let callbacks = MockPlayerCallbacks()
  player.callbacks = callbacks
  try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T", live: true)], playWhenReady: true)
  engine.hasItem = true
  engine.hasLoadedAsset = true
  engine.emit(.itemPrepared(likelyToKeepUp: true))
  return (player, engine, callbacks, clock)
}

/// A live stream connecting, its retry loop on a virtual clock; the engine reports `previousBuffered`
/// from the item the connect replaces until the test prepares one.
@MainActor
private func makeConnecting(
  playWhenReady: Bool = true,
  previousBuffered: Double = 0,
  live: Bool = true,
) throws -> (Player, MockPlayerEngine, VirtualClock) {
  let clock = VirtualClock()
  let retry = RetryLoop(clock: clock)
  retry.updatePolicy(from: .first(true))
  let engine = MockPlayerEngine()
  engine.bufferedPosition = previousBuffered
  let player = makePlayer(engine: engine, retry: retry)
  try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T", live: live)], playWhenReady: playWhenReady)
  return (player, engine, clock)
}

@Suite("Player - seeking")
@MainActor
struct PlayerSeekingTests {
  @Test func aSeekWhileLoadingWaitsForTheItem_andHoldsReadyUntilItLands() throws {
    let (player, engine, _) = try makeLoading()
    engine.seekResult = nil
    player.seek(to: 30)
    #expect(engine.seekCalls.isEmpty)

    engine.hasItem = true
    engine.hasLoadedAsset = true
    engine.emit(.itemPrepared(likelyToKeepUp: true))
    #expect(engine.seekCalls == [30])
    #expect(player.state == .loading)
  }

  @Test func theLoadBecomesReadyWhenTheDeferredSeekLands() throws {
    let (player, engine, _) = try makeLoading()
    player.seek(to: 30)
    engine.hasItem = true
    engine.hasLoadedAsset = true
    engine.emit(.itemPrepared(likelyToKeepUp: false))
    #expect(engine.seekCalls == [30])
    #expect(player.state == .ready)
  }

  @Test func seekByWhileLoadingOffsetsTheDeferredTarget() throws {
    let (player, _, _) = try makeLoading()
    player.seek(to: 30)
    player.seek(by: 15)
    #expect(player.deferredSeek.target == 45)
  }

  @Test func aCompletionFromBeforeATrackChangeIsDropped() throws {
    let (player, engine, _) = try makePlaying()
    engine.seekResult = nil
    var landed: [Bool] = []
    player.seek(to: 10) { landed.append($0) }

    try player.setQueue([Track(id: "t2", src: "https://example.com/b.mp3", title: "B")], startPositionMs: 25000)
    engine.completeSeeks(true)

    // The old item's seek reports false; the new track's start stays pending.
    #expect(landed == [false])
    #expect(player.deferredSeek.target == 25)
    #expect(player.state == .loading)
  }

  @Test func aCompletionFromBeforeAReloadIsDropped() throws {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine)
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")], startPositionMs: 25000)
    engine.seekResult = nil
    engine.hasItem = true
    engine.emit(.itemPrepared(likelyToKeepUp: true))

    player.reloadResolving(startFromCurrentTime: true)
    engine.completeSeeks(true)
    engine.completeSeeks(true)

    #expect(player.state == .loading)
    #expect(player.deferredSeek.target == 25)
    #expect(engine.seekCalls == [25])
    engine.emit(.itemPrepared(likelyToKeepUp: true))
    #expect(engine.seekCalls == [25, 25])
  }

  @Test func aStartSeekCutShortWaitsForTheNextItem() throws {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine)
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")], startPositionMs: 25000)
    engine.seekResult = nil
    engine.hasItem = true
    engine.emit(.itemPrepared(likelyToKeepUp: true))

    engine.completeSeeks(false)

    #expect(player.state == .loading)
    #expect(player.deferredSeek.target == 25)
    #expect(engine.seekCalls == [25])
  }

  /// A media-load retry passes no position, so the start in flight is what it resumes from.
  @Test func aReloadThatDoesNotResumeKeepsTheStartInFlight() throws {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine)
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")], startPositionMs: 25000)
    engine.seekResult = nil
    engine.hasItem = true
    engine.emit(.itemPrepared(likelyToKeepUp: true))

    player.reloadResolving(startFromCurrentTime: false)
    engine.completeSeeks(true)

    #expect(player.state == .loading)
    engine.emit(.itemPrepared(likelyToKeepUp: true))
    #expect(engine.seekCalls == [25, 25])
  }

  @Test func aMediaServicesResetDuringTheLoadKeepsTheStart() throws {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine)
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")], startPositionMs: 25000)

    player.mediaServicesWereReset()
    engine.hasItem = true
    engine.emit(.itemPrepared(likelyToKeepUp: true))

    #expect(engine.seekCalls == [25])
  }

  @Test func aPlayRightAfterAStopDuringTheStartSeekStartsOver() throws {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine)
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")], startPositionMs: 25000)
    engine.seekResult = nil
    engine.hasItem = true
    engine.emit(.itemPrepared(likelyToKeepUp: true))

    player.stop()
    player.play()
    engine.emit(.itemPrepared(likelyToKeepUp: true))

    #expect(engine.seekCalls == [25, 0])
    #expect(player.deferredSeek.target == nil)
  }

  @Test func theLiveEdgeDuringALoadWaitsForTheStartSeek() throws {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine)
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.m3u8", title: "T", live: true)], startPositionMs: 25000)
    player.play()
    engine.seekResult = nil
    engine.hasItem = true
    engine.hasLoadedAsset = true
    engine.liveEdge = 120
    engine.emit(.itemPrepared(likelyToKeepUp: true))

    player.seekToLiveEdge()
    #expect(engine.seekCalls == [25])
    engine.completeSeeks(true)
    engine.completeSeeks(true)

    #expect(engine.seekCalls == [25, 120])
    #expect(player.state != .loading)
  }

  @Test func theLiveEdgeIsTheSeekableWindowsEnd_orAReconnectWithoutOne() throws {
    let (player, engine, _) = try makePlaying(live: true)
    engine.liveEdge = 120
    player.seekToLiveEdge()
    #expect(engine.seekCalls == [120])

    engine.liveEdge = nil
    player.seekToLiveEdge()
    #expect(engine.resolveAndLoadCalls.count == 2)
    #expect(player.state == .loading)
  }

  @Test func theLiveEdgeInAnErrorIsAReconnect() throws {
    let (player, engine, _) = try makePlaying(live: true)
    engine.liveEdge = 120
    engine.emit(.failed(nil, httpStatusCode: 404))
    player.seekToLiveEdge()
    engine.emit(.itemPrepared(likelyToKeepUp: true))
    #expect(engine.seekCalls.isEmpty)
    #expect(engine.resolveAndLoadCalls.count == 2)
  }
}

@Suite("Player - engine events")
@MainActor
struct PlayerEngineEventTests {
  @Test func aFailedLoadPrimesNowPlayingOncePerLoad() throws {
    let (player, engine, _) = try makeLoading()
    player.setPlayWhenReady(true)
    engine.emit(.loadFailed(URLError(.timedOut)))
    engine.emit(.loadFailed(URLError(.timedOut)))
    #expect(engine.primeCount == 1)

    try player.setQueue([Track(id: "t2", src: "https://example.com/b.mp3", title: "B")])
    engine.emit(.loadFailed(URLError(.timedOut)))
    #expect(engine.primeCount == 2)
  }

  @Test func aFailedLoadWithoutIntentDoesNotPrime_andWaitsForAPlay() throws {
    let (player, engine, _) = try makeLoading()
    engine.emit(.loadFailed(URLError(.timedOut)))
    #expect(engine.primeCount == 0)
    #expect(player.state == .paused)

    player.setPlayWhenReady(true)
    #expect(engine.resolveAndLoadCalls.count == 2)
  }

  @Test func aPauseWhileLoadingReachesTheEngine_aPlayDoesNot() throws {
    let (player, engine, _) = try makeLoading()
    player.setPlayWhenReady(true)
    engine.hasItem = true
    engine.hasLoadedAsset = true
    engine.emit(.itemPrepared(likelyToKeepUp: false))
    #expect(player.state == .loading)
    let plays = engine.startPlaybackCallCount
    let pauses = engine.pausePlaybackCallCount

    player.setPlayWhenReady(false)
    #expect(engine.pausePlaybackCallCount == pauses + 1)

    player.setPlayWhenReady(true)
    #expect(engine.startPlaybackCallCount == plays)
    engine.emit(.likelyToKeepUp(true))
    #expect(engine.startPlaybackCallCount == plays + 1)
  }

  @Test func anUnplayableAssetIsTerminal() throws {
    let (player, engine, _) = try makeLoading()
    engine.emit(.unplayable)
    #expect(player.state == .error)
    #expect(player.errorStatus == .terminal(.trackWasUnplayable, report: 1))
  }

  @Test func anInvalidSourceIsTerminal() throws {
    let (player, engine, _) = try makeLoading()
    engine.emit(.invalidSource("::"))
    #expect(player.errorStatus == .terminal(.invalidSourceUrl("::"), report: 1))
  }

  @Test func aPlayerFailureCarriesItsHTTPStatus() throws {
    let (player, engine, _) = try makePlaying()
    engine.emit(.failed(nil, httpStatusCode: 404))
    #expect(player.errorStatus == .terminal(.httpStatus(404), report: 1))
  }

  @Test func aGenuineEndAdvances_aLiveEndReconnectsThroughTheRetryLoop() async throws {
    let (player, engine, _) = try makePlaying()
    engine.currentTime = 300
    engine.duration = 300
    engine.emit(.playedToEnd)
    #expect(player.state == .ended)

    let (live, liveEngine, _) = try makePlaying(live: true)
    liveEngine.emit(.playedToEnd)
    await live.retry.pendingTask?.value
    #expect(live.state == .loading)
    #expect(liveEngine.resolveAndLoadCalls.count == 2)
  }

  /// AVPlayer pauses an item at its end, so a seek within its window would leave it silent.
  @Test func aLiveEndReconnectsEvenWithASeekableWindow() async throws {
    let (player, engine, _) = try makePlaying(live: true)
    engine.liveEdge = 120
    engine.emit(.playedToEnd)
    await player.retry.pendingTask?.value
    #expect(engine.seekCalls.isEmpty)
    #expect(engine.resolveAndLoadCalls.count == 2)
  }

  @Test func liveEndsThatKeepComingEndInATerminalErrorWithinTheBudget() async throws {
    let (player, engine, callbacks) = try makePlaying(live: true)
    player.retry.updatePolicy(from: .second(RetryConfig(
      maxRetries: 3, maxRetryDurationMs: nil, firstConnectMaxRetryDurationMs: nil,
    )))
    for _ in 0 ..< 20 where player.state != .error {
      engine.emit(.playedToEnd)
      await player.retry.pendingTask?.value
      engine.hasLoadedAsset = true
      engine.emit(.itemPrepared(likelyToKeepUp: true))
    }
    // The first connection played, so its end starts the count: four that deliver nothing follow.
    #expect(engine.resolveAndLoadCalls.count == 5)
    #expect(player.state == .error)
    #expect(player.errorStatus.error == .playbackStalled)
    #expect(callbacks.errorEvents.count == 1)
  }

  /// A connection that delivered audio starts `maxRetries`' count over (docs/adr/0014).
  @Test func liveEndsBetweenAudioNeverSpendMaxRetries() async throws {
    let (player, engine, callbacks) = try makePlaying(live: true)
    player.retry.updatePolicy(from: .second(RetryConfig(
      maxRetries: 1, maxRetryDurationMs: nil, firstConnectMaxRetryDurationMs: nil,
    )))
    for _ in 0 ..< 5 {
      engine.emit(.playedToEnd)
      await player.retry.pendingTask?.value
      engine.emit(.itemPrepared(likelyToKeepUp: true))
      player.transition(.avPlayerPlaying)
    }
    #expect(engine.resolveAndLoadCalls.count == 6)
    #expect(player.state == .playing)
    #expect(callbacks.errorEvents.isEmpty)
  }

  @Test func aLiveEndWithRetryOffIsTerminal_withNoBufferingOnTheWay() throws {
    let (player, engine, callbacks) = try makePlaying(live: true)
    player.retry.updatePolicy(from: nil)
    let changesBefore = callbacks.playbackChanges.count
    engine.emit(.playedToEnd)
    #expect(player.state == .error)
    #expect(engine.resolveAndLoadCalls.count == 1)
    #expect(callbacks.playbackChanges.dropFirst(changesBefore).map(\.state) == [.error])
  }

  /// The engine stops observing an item on a reload; a played-to-end it delivered late is stale.
  @Test func aPlayedToEndDuringALoadIsIgnored() throws {
    let (player, engine, _) = try makePlaying(live: true)
    player.reloadResolving(startFromCurrentTime: false)
    #expect(player.state == .loading)
    engine.emit(.playedToEnd)
    #expect(player.state == .loading)
    #expect(player.retry.pendingTask == nil)
  }

  /// A truncated file stalls rather than ending (the stall watch's); a short end is a duration
  /// AVPlayer overestimated, as for a VBR file without a header.
  @Test func anOnDemandEndShortOfItsEstimatedDurationAdvances() throws {
    let (player, engine, _) = try makePlaying()
    try player.add([Track(id: "t2", src: "https://example.com/b.mp3", title: "B")])
    engine.currentTime = 120
    engine.duration = 300

    engine.emit(.playedToEnd)

    #expect(player.queue.currentIndex == 1)
    #expect(player.retry.pendingTask == nil)
  }

  @Test func aStallNudgesPlayback_onlyWithIntent() throws {
    let (player, engine, _) = try makePlaying()
    engine.startPlaybackCallCount = 0
    engine.emit(.stalled)
    #expect(engine.startPlaybackCallCount == 1)

    player.pause()
    engine.startPlaybackCallCount = 0
    engine.emit(.stalled)
    #expect(engine.startPlaybackCallCount == 0)
  }

  @Test func anAirPlayStrandingIsNudged() throws {
    let (player, engine, _) = try makePlaying()
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
  @Test func aStallThatOutlastsTheTimeoutIsRetried() async throws {
    let (player, engine, callbacks) = try makeStalling()
    await player.retry.stallTask?.value
    #expect(callbacks.playbackChanges.last?.error?.code == TrackPlayerError.PlaybackError.playbackStalled.code)
    #expect(callbacks.playbackChanges.last?.error?.retrying == true)
    await player.retry.pendingTask?.value
    #expect(engine.resolveAndLoadCalls.count == 2)
    #expect(player.state == .loading)
  }

  @Test func aStallWithNoFailureEndsInPlaybackStalledOnceTheBudgetIsSpent() async throws {
    let (player, engine, callbacks) = try makeStalling(givesUp: true)
    await player.retry.stallTask?.value
    await player.retry.pendingTask?.value
    // The stalled connection had played, so even `maxRetries: 0` reconnects it once.
    #expect(engine.resolveAndLoadCalls.count == 2)
    player.transition(.avPlayerWaiting)
    await player.retry.stallTask?.value
    await player.retry.pendingTask?.value
    #expect(player.state == .error)
    #expect(player.errorStatus.error == .playbackStalled)
    #expect(callbacks.errorEvents.count == 1)
  }

  @Test func aStallThatRecoversIsNotRetried() async throws {
    let (player, engine, _) = try makeStalling()
    player.transition(.avPlayerPlaying)
    await player.retry.stallTask?.value
    #expect(player.retry.pendingTask == nil)
    #expect(engine.resolveAndLoadCalls.count == 1)
  }

  /// Without `automaticallyWaitsToMinimizeStalling`, AVPlayer reports a stall while the state
  /// stays .playing, and audio may well be playing.
  @Test func aStallReportedWhilePlayingStartsNoWatch() throws {
    let (player, engine, _) = try makePlaying()
    engine.emit(.stalled)
    #expect(player.state == .playing)
    #expect(player.retry.stallTask == nil)
  }

  @Test func aResumeIntoBufferingStartsANewWatch() throws {
    let (player, engine, _) = try makeStalling()
    player.pause()
    engine.emit(.timeControlStatus(.paused, waitingForNoItem: false))
    player.play()
    engine.emit(.timeControlStatus(.waitingToPlayAtSpecifiedRate, waitingForNoItem: false))
    #expect(player.state == .buffering)
    #expect(player.retry.stallTask != nil)
    player.retry.cancel()
  }

  @Test func aPauseEndsTheStallWatch() throws {
    let (player, engine, _) = try makeStalling()
    player.pause()
    engine.emit(.timeControlStatus(.paused, waitingForNoItem: false))
    #expect(player.retry.stallTask == nil)
    #expect(player.load?.reloadOnPlay == false)
    #expect(player.state == .paused)
  }

  @Test func aTrackChangeEndsTheStallWatch() throws {
    let (player, _, _) = try makeStalling()
    let stallWatch = player.retry.stallTask
    try player.setQueue([Track(id: "t2", src: "https://example.com/b.mp3", title: "B")])
    #expect(stallWatch?.isCancelled == true)
  }

  @Test func aConnectThatSendsNothingIsReplacedAfterTheTimeout() async throws {
    let (player, engine, clock) = try makeConnecting()
    await clock.run(until: 30_999)
    #expect(engine.resolveAndLoadCalls.count == 1)
    await clock.run(until: 31_000)
    #expect(engine.resolveAndLoadCalls.count == 2)
    #expect(player.state == .loading)
  }

  /// The item it replaces had buffered further than the new one has yet.
  @Test func aSlowConnectThatKeepsReceivingDataIsKept() async throws {
    let (player, engine, clock) = try makeConnecting(previousBuffered: 50)
    engine.hasItem = true
    engine.bufferedPosition = 1
    engine.emit(.itemPrepared(likelyToKeepUp: false))
    for atMs in stride(from: 20_000, through: 100_000, by: 20_000) {
      clock.at(atMs) { engine.bufferedPosition += 1 }
    }
    await clock.run(until: 100_000)
    #expect(player.state == .loading)
    #expect(engine.resolveAndLoadCalls.count == 1)
  }

  /// A stall's reconnect: the stalled item stays in the engine, still buffering, while the new
  /// connect sends nothing.
  @Test func theItemAConnectReplacesBufferingOnIsNoProgress() async throws {
    let (player, engine, _, clock) = try makeVirtual()
    engine.bufferedPosition = 50
    player.transition(.avPlayerPlaying)
    await clock.run(until: 10_000)
    player.transition(.avPlayerWaiting)
    await clock.run(until: 40_000)
    #expect(engine.resolveAndLoadCalls.count == 2)

    for atMs in stride(from: 45_000, through: 70_000, by: 5_000) {
      clock.at(atMs) { engine.bufferedPosition += 1 }
    }
    await clock.run(until: 70_000)
    await clock.run(until: 71_000)
    #expect(engine.resolveAndLoadCalls.count == 3)
  }

  @Test func aPauseDuringAnOnDemandConnectHoldsItPastTheTimeout() async throws {
    let (player, engine, clock) = try makeConnecting(live: false)
    await clock.run(until: 10_000)
    player.pause()
    await clock.run(until: 200_000)
    #expect(engine.resolveAndLoadCalls.count == 1)
    #expect(player.load?.reloadOnPlay == false)
    #expect(player.state == .loading)
  }

  @Test func withRetryOffASilentConnectIsTerminalAtTheTimeout() async throws {
    let (player, engine, clock) = try makeConnecting()
    player.retry.updatePolicy(from: nil)
    await clock.run(until: 29_999)
    #expect(player.state == .loading)
    await clock.run(until: 30_000)
    #expect(player.state == .error)
    #expect(player.errorStatus.error == .playbackStalled)
    #expect(engine.resolveAndLoadCalls.count == 1)
  }

  /// On demand: a live stream queued without play intent does not connect.
  @Test func aPlayWhileConnectingWatchesTheConnect() async throws {
    let (player, engine, clock) = try makeConnecting(playWhenReady: false, live: false)
    await clock.run(until: 10_000)
    player.play()
    await clock.run(until: 41_000)
    #expect(engine.resolveAndLoadCalls.count == 2)
  }

  /// Ready and waiting for AVPlayer to start: its waiting is a stall, watched in .buffering.
  @Test func aConnectThatIsReadyIsNoLongerWatched() async throws {
    let (player, engine, clock) = try makeConnecting()
    engine.hasItem = true
    engine.hasLoadedAsset = true
    engine.emit(.itemPrepared(likelyToKeepUp: true))
    #expect(player.state == .ready)
    await clock.run(until: 100_000)
    #expect(engine.resolveAndLoadCalls.count == 1)
  }

  /// A link slower than the bitrate rebuffers for longer than the timeout, and a reload would
  /// throw away the buffer it is filling.
  @Test func aStallThatStillReceivesDataKeepsWaiting_untilAWindowBringsNone() async throws {
    let (player, engine, _) = try makeStalling()
    engine.bufferedPosition = 12
    await player.retry.stallTask?.value
    #expect(player.retry.pendingTask == nil)
    #expect(player.retry.stallTask != nil)
    #expect(engine.resolveAndLoadCalls.count == 1)

    await player.retry.stallTask?.value
    await player.retry.pendingTask?.value
    #expect(engine.resolveAndLoadCalls.count == 2)
  }

  /// Near a track's end, a pause leaves the state in .buffering, so the resume is no transition.
  @Test func aResumeWhileTheStateHeldBufferingStartsANewWatch() async throws {
    let (player, _, _) = try makeStalling()
    player.pause()
    await player.retry.stallTask?.value
    #expect(player.state == .buffering)
    #expect(player.retry.stallTask == nil)

    player.play()
    #expect(player.retry.stallTask != nil)
    player.retry.cancel()
  }

  /// The stalled item is still alive: data arriving later must not play it under the error.
  @Test func aTerminalStallLeavesNothingThatCanPlay() async throws {
    let (player, engine, _) = try makeStalling()
    player.retry.updatePolicy(from: nil)
    let pauses = engine.pausePlaybackCallCount
    await player.retry.stallTask?.value
    #expect(player.state == .error)
    #expect(engine.pausePlaybackCallCount == pauses + 1)

    engine.startPlaybackCallCount = 0
    engine.emit(.stalled)
    #expect(engine.startPlaybackCallCount == 0)
  }

  @Test func anAirPlayStrandingInAnErrorIsNotNudged() throws {
    let (player, engine, _) = try makePlaying()
    engine.isAirPlayRoute = true
    engine.emit(.timeControlStatus(.waitingToPlayAtSpecifiedRate, waitingForNoItem: true))
    player.transition(.errorOccurred(.playbackStalled))
    engine.startPlaybackCallCount = 0
    engine.emit(.timeControlStatus(.paused, waitingForNoItem: false))
    #expect(engine.startPlaybackCallCount == 0)
  }

  @Test func aStallWithRetryOffIsTerminalAtTheTimeout() async throws {
    let (player, engine, _) = try makeStalling()
    player.retry.updatePolicy(from: nil)
    await player.retry.stallTask?.value
    #expect(player.state == .error)
    #expect(player.errorStatus.error == .playbackStalled)
    #expect(engine.resolveAndLoadCalls.count == 1)
  }

  @Test func theDeadlineEndsASilentLoadInOneError_droppingTheConnectInFlight() async throws {
    let clock = VirtualClock()
    let retry = RetryLoop(clock: clock)
    retry.updatePolicy(from: .first(true))
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine, retry: retry)
    let callbacks = MockPlayerCallbacks()
    player.callbacks = callbacks
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")], playWhenReady: true)
    // The watch replaces the silent connect at 30 s, 1 s after it gives up on it; that failure
    // brings the deadline to 42 s.
    await clock.run(until: 31_000)
    #expect(engine.resolveAndLoadCalls.count == 2)
    let cancels = engine.cancelLoadingCount, stops = engine.stopObservingItemCount

    await clock.run(until: 41_999)
    #expect(player.state == .loading)
    await clock.run(until: 42_000)

    #expect(player.state == .error)
    #expect(player.errorStatus.error == .playbackStalled)
    #expect(callbacks.errorEvents.count == 1)
    #expect(engine.cancelLoadingCount == cancels + 1)
    #expect(engine.stopObservingItemCount == stops + 1)
  }

  /// The error pauses AVPlayer, and an item still connecting can report late: neither the pause,
  /// nor its playing, nor its failure takes the state out of .error.
  @Test func aTerminalErrorHoldsAgainstTheAVPlayersLateEvents() async throws {
    let (player, engine, callbacks) = try makeStalling(givesUp: true)
    engine.emit(.failed(URLError(.badURL), httpStatusCode: nil))
    #expect(player.state == .error)
    let loads = engine.resolveAndLoadCalls.count

    engine.emit(.timeControlStatus(.paused, waitingForNoItem: false))
    engine.emit(.timeControlStatus(.playing, waitingForNoItem: false))
    engine.emit(.audioDidStart)
    engine.emit(.failed(NSError(domain: "AVFoundationErrorDomain", code: -11800), httpStatusCode: nil))
    await player.retry.pendingTask?.value

    #expect(player.state == .error)
    #expect(player.errorStatus.error == .playbackFailed)
    #expect(callbacks.errorEvents.count == 1)
    #expect(player.retry.pendingTask == nil)
    #expect(engine.resolveAndLoadCalls.count == loads)
  }

  /// The stalled connection ends with the network, before its return reaches the loop, so it
  /// grows no backoff: only the 1 s floor applies, long past.
  @Test func theNetworkReturningReconnectsAStalledStreamAtOnce() async throws {
    let (player, engine, _, clock) = try makeVirtual(online: false)
    player.transition(.avPlayerWaiting)
    #expect(player.state == .buffering)
    await clock.run(until: 5_000)

    player.networkStatusDidChange(isOnline: true)
    await clock.run(until: 5_000)

    #expect(engine.resolveAndLoadCalls.count == 2)
  }

  /// A short drop leaves the connection dead with audio still buffered: the stall comes after the
  /// network is back, and AVPlayer never fails it.
  @Test func aStallAfterAShortDropReconnectsAtOnce() async throws {
    let (player, engine, _, clock) = try makeVirtual()
    engine.bufferedPosition = 20
    player.transition(.avPlayerPlaying)
    await clock.run(until: 10_000)
    player.networkStatusDidChange(isOnline: false)
    await clock.run(until: 13_000)
    player.networkStatusDidChange(isOnline: true)
    await clock.run(until: 15_000)

    player.transition(.avPlayerWaiting)
    await clock.run(until: 15_000)

    #expect(engine.resolveAndLoadCalls.count == 2)
  }

  @Test func aConnectionThatBuffersAfterTheDropWaitsOutTheWatch() async throws {
    let (player, engine, _, clock) = try makeVirtual()
    engine.bufferedPosition = 20
    player.transition(.avPlayerPlaying)
    player.networkStatusDidChange(isOnline: false)
    player.networkStatusDidChange(isOnline: true)
    await clock.run(until: 5_000)
    engine.bufferedPosition = 25

    player.transition(.avPlayerWaiting)
    await clock.run(until: 34_999)
    #expect(engine.resolveAndLoadCalls.count == 1)
    await clock.run(until: 35_000)
    #expect(engine.resolveAndLoadCalls.count == 2)
  }

  @Test func aConnectionStartedAfterTheDropWaitsOutTheWatch() async throws {
    let (player, engine, _, clock) = try makeVirtual()
    player.networkStatusDidChange(isOnline: false)
    player.networkStatusDidChange(isOnline: true)
    player.retryNow()
    engine.emit(.itemPrepared(likelyToKeepUp: true))
    player.transition(.avPlayerPlaying)
    await clock.run(until: 5_000)

    player.transition(.avPlayerWaiting)
    await clock.run(until: 34_999)
    #expect(engine.resolveAndLoadCalls.count == 2)
    await clock.run(until: 35_000)
    #expect(engine.resolveAndLoadCalls.count == 3)
  }

  /// What arrives after the drop is reported was on its way before it.
  @Test func audioDrainingInDuringTheDropLeavesTheConnectionDead() async throws {
    let (player, engine, _, clock) = try makeVirtual()
    engine.bufferedPosition = 20
    player.transition(.avPlayerPlaying)
    await clock.run(until: 10_000)
    player.networkStatusDidChange(isOnline: false)
    engine.bufferedPosition = 20.4
    player.networkStatusDidChange(isOnline: true)

    player.transition(.avPlayerWaiting)
    await clock.run(until: 10_000)

    #expect(engine.resolveAndLoadCalls.count == 2)
  }

  @Test func aSecondDropKillsAConnectionThatLivedThroughTheFirst() async throws {
    let (player, engine, _, clock) = try makeVirtual()
    engine.bufferedPosition = 20
    player.transition(.avPlayerPlaying)
    await clock.run(until: 10_000)
    player.networkStatusDidChange(isOnline: false)
    player.networkStatusDidChange(isOnline: true)
    engine.bufferedPosition = 80
    await clock.run(until: 60_000)
    player.networkStatusDidChange(isOnline: false)
    player.networkStatusDidChange(isOnline: true)

    player.transition(.avPlayerWaiting)
    await clock.run(until: 60_000)

    #expect(engine.resolveAndLoadCalls.count == 2)
  }

  @Test func aSeekAfterTheDropWaitsForItsData_andPlayingAgainClearsTheDrop() async throws {
    let (player, engine, _, clock) = try makeVirtual()
    player.transition(.avPlayerPlaying)
    player.networkStatusDidChange(isOnline: false)
    player.networkStatusDidChange(isOnline: true)
    player.transition(.avPlayerWaiting)
    // The 1 s floor from the first connect's start.
    await clock.run(until: 1_000)
    #expect(engine.resolveAndLoadCalls.count == 2)
    engine.emit(.itemPrepared(likelyToKeepUp: true))
    player.transition(.avPlayerPlaying)
    player.networkStatusDidChange(isOnline: false)
    player.networkStatusDidChange(isOnline: true)
    player.seek(to: 300)
    player.transition(.avPlayerWaiting)
    await clock.run(until: 2_000)
    #expect(engine.resolveAndLoadCalls.count == 2)
    player.transition(.avPlayerPlaying)
    await clock.run(until: 5_000)

    player.transition(.avPlayerWaiting)
    await clock.run(until: 34_999)
    #expect(engine.resolveAndLoadCalls.count == 2)
    await clock.run(until: 35_000)
    #expect(engine.resolveAndLoadCalls.count == 3)
  }

  /// A connect that delivered nothing ends online, and counts toward `maxRetries`: only the watch
  /// gives it up, as without the drop.
  /// On demand: a live stream queued without play intent does not connect.
  @Test func aSilentConnectFromBeforeTheDropWaitsOutTheWatch() async throws {
    let clock = VirtualClock()
    let retry = RetryLoop(online: false, clock: clock)
    retry.updatePolicy(from: .second(RetryConfig(
      maxRetries: 0, maxRetryDurationMs: nil, firstConnectMaxRetryDurationMs: nil,
    )))
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine, retry: retry)
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")])
    player.networkStatusDidChange(isOnline: true)

    player.play()
    await clock.run(until: 29_999)

    #expect(player.state == .loading)
    #expect(engine.resolveAndLoadCalls.count == 1)
  }

  @Test func aStallAfterAShortDropWithRetryOffIsLeftToAVPlayer() async throws {
    let (player, engine, callbacks, clock) = try makeVirtual()
    player.retry.updatePolicy(from: nil)
    player.transition(.avPlayerPlaying)
    player.networkStatusDidChange(isOnline: false)
    player.networkStatusDidChange(isOnline: true)

    player.transition(.avPlayerWaiting)
    await clock.run(until: 1_000)

    #expect(player.state == .buffering)
    #expect(engine.resolveAndLoadCalls.count == 1)
    #expect(callbacks.errorEvents.isEmpty)
  }

  /// The stall that follows a short drop ends at the deadline, 120 s into its silence, when no
  /// connection after it delivers audio.
  @Test func aStallAfterAShortDropStillEndsAtTheDeadline() async throws {
    let (player, engine, callbacks, clock) = try makeVirtual()
    player.transition(.avPlayerPlaying)
    await clock.run(until: 10_000)
    player.networkStatusDidChange(isOnline: false)
    await clock.run(until: 13_000)
    player.networkStatusDidChange(isOnline: true)
    await clock.run(until: 15_000)
    player.transition(.avPlayerWaiting)

    await clock.run(until: 134_999)
    #expect(player.state == .loading)
    #expect(engine.resolveAndLoadCalls.count > 1)
    await clock.run(until: 135_000)
    #expect(player.state == .error)
    #expect(player.errorStatus.error == .playbackStalled)
    #expect(callbacks.errorEvents.count == 1)
  }

  /// The watch replaces the stall at 40 s at once, having played, then each silent connect 30 s
  /// after it starts, 1 s and then 1.5 s later: the deadline still falls 120 s into the silence.
  @Test func playbackThatStopsWithNoFailureEndsAtTheDeadline() async throws {
    let (player, engine, callbacks, clock) = try makeVirtual()
    player.transition(.avPlayerPlaying)
    await clock.run(until: 10_000)
    player.transition(.avPlayerWaiting)

    await clock.run(until: 129_999)
    #expect(player.state == .loading)
    #expect(engine.resolveAndLoadCalls.count == 4)
    await clock.run(until: 130_000)
    #expect(player.state == .error)
    #expect(player.errorStatus.error == .playbackStalled)
    #expect(callbacks.errorEvents.count == 1)
  }

  @Test func aRetryNowKeepsTheSilenceDeadline() async throws {
    let (player, _, _, clock) = try makeVirtual()
    // Its watch would end the reload's connect first.
    player.retry.stallTimeout = .seconds(600)
    await clock.run(until: 60_000)
    player.retryNow()

    await clock.run(until: 119_999)
    #expect(player.state != .error)
    await clock.run(until: 120_000)
    #expect(player.state == .error)
  }

  @Test func hasPlayedSurvivesAPark_aRetryNowAndAMediaReset() async throws {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine, retry: makeRetryLoop(delay: 0.05))
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")], playWhenReady: true)
    engine.hasItem = true
    engine.hasLoadedAsset = true
    engine.emit(.itemPrepared(likelyToKeepUp: true))
    player.transition(.avPlayerPlaying)
    #expect(player.retry.hasPlayed)

    engine.emit(.failed(URLError(.timedOut), httpStatusCode: nil))
    player.pause()
    #expect(player.state == .paused)
    player.play()
    player.retryNow()
    player.mediaServicesWereReset()

    #expect(player.retry.hasPlayed)
    player.retry.cancel()
  }

  @Test func aRateChangeInAnErrorLeavesTheAbandonedItemPaused() throws {
    let (player, engine, _) = try makePlaying()
    engine.emit(.failed(URLError(.badURL), httpStatusCode: nil))
    #expect(player.state == .error)
    #expect(player.playWhenReady)

    player.rate = 1.5

    #expect(engine.rates.last?.rate == 1.5)
    #expect(engine.rates.last?.playing == false)
  }

  /// `maxRetries` counts connections in a row that delivered nothing; one that plays between
  /// flaps starts it over, and only the silence deadline bounds it (docs/adr/0014).
  @Test func aFlappingConnectionThatPlaysBetweenKeepsReconnecting() async throws {
    let (player, engine, callbacks) = try makePlaying()
    player.retry.updatePolicy(from: .second(RetryConfig(
      maxRetries: 2, maxRetryDurationMs: nil, firstConnectMaxRetryDurationMs: nil,
    )))
    for _ in 1 ... 5 {
      player.transition(.avPlayerWaiting)
      player.networkStatusDidChange(isOnline: false)
      player.networkStatusDidChange(isOnline: true)
      await player.retry.pendingTask?.value
      engine.emit(.itemPrepared(likelyToKeepUp: true))
      player.transition(.avPlayerPlaying)
    }
    #expect(engine.resolveAndLoadCalls.count == 6)
    #expect(player.state == .playing)
    #expect(callbacks.errorEvents.isEmpty)
  }

  @Test func theNetworkReturningWithRetryOffLeavesTheStallToAVPlayer() throws {
    let (player, engine, callbacks) = try makePlaying()
    player.retry.updatePolicy(from: nil)
    player.transition(.avPlayerWaiting)
    player.networkStatusDidChange(isOnline: false)
    player.networkStatusDidChange(isOnline: true)
    #expect(player.state == .buffering)
    #expect(engine.resolveAndLoadCalls.count == 1)
    #expect(callbacks.errorEvents.isEmpty)
  }

  @Test func theNetworkReturningLeavesAPausedOrPlayingStreamAlone() throws {
    let (player, engine, _) = try makePlaying()
    player.networkStatusDidChange(isOnline: false)
    player.networkStatusDidChange(isOnline: true)
    player.pause()
    player.networkStatusDidChange(isOnline: false)
    player.networkStatusDidChange(isOnline: true)
    #expect(engine.resolveAndLoadCalls.count == 1)
  }

  @Test func theNetworkReturningDuringABackoffLeavesTheReloadToTheRetry() async throws {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine, retry: makeRetryLoop(delay: 0.05))
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")])
    player.play()
    player.transition(.avPlayerWaiting)
    engine.emit(.failed(URLError(.timedOut), httpStatusCode: nil))
    let retrying = player.retry.pendingTask
    let loadsBefore = engine.resolveAndLoadCalls.count

    player.networkStatusDidChange(isOnline: false)
    player.networkStatusDidChange(isOnline: true)
    await retrying?.value

    #expect(player.state == .loading)
    #expect(engine.resolveAndLoadCalls.count == loadsBefore + 1)
  }

  @Test func theNetworkGoingReRendersTheStallLineWithoutReconnecting() throws {
    let (player, engine, _) = try makePlaying()
    player.transition(.avPlayerWaiting)
    engine.nowPlayingRenderCount = 0

    player.networkStatusDidChange(isOnline: false)

    #expect(engine.nowPlayingRenderCount == 1)
    #expect(engine.resolveAndLoadCalls.count == 1)
  }

  @Test func theNetworkReturningReRendersBeforeReconnecting() async throws {
    let (player, engine, _) = try makePlaying()
    player.transition(.avPlayerWaiting)
    player.networkStatusDidChange(isOnline: false)
    var order: [String] = []
    engine.onSeamCall = { order.append($0) }
    let loadsBefore = engine.resolveAndLoadCalls.count

    player.networkStatusDidChange(isOnline: true)
    await player.retry.pendingTask?.value

    #expect(order.first == "renderNowPlaying")
    #expect(engine.resolveAndLoadCalls.count == loadsBefore + 1)
  }

  @Test func aRestartFromAnErrorBeginsANewLoad() async throws {
    let (player, engine, _) = try makePlaying()
    engine.emit(.audioDidStart)
    let first = player.load
    #expect(player.retry.hasPlayed)

    engine.emit(.unplayable)
    #expect(player.state == .error)
    player.play()

    #expect(player.load?.id != first?.id)
    #expect(!player.retry.hasPlayed)
    #expect(player.load?.primedNowPlaying == false)
    #expect(engine.recreateCount == 1)
  }

  @Test func aRetryKeepsItsLoad() async throws {
    let (player, engine, _) = try makePlaying()
    let first = player.load
    engine.emit(.failed(URLError(.timedOut), httpStatusCode: nil))
    await player.retry.pendingTask?.value
    #expect(player.load?.id == first?.id)
    #expect(engine.recreateCount == 0)
  }

  @Test func aRetryAfterTheAVPlayerItselfFailedReloadsOnANewOne_inTheSameLoad() async throws {
    let (player, engine, _) = try makePlaying()
    engine.emit(.audioDidStart)
    let first = player.load
    engine.playerFailed = true

    engine.emit(.failed(URLError(.networkConnectionLost), httpStatusCode: nil))
    await player.retry.pendingTask?.value

    #expect(engine.recreateCount == 1)
    #expect(engine.resolveAndLoadCalls.count == 2)
    #expect(player.load?.id == first?.id)
    #expect(player.retry.hasPlayed)
  }

  @Test func aMediaServicesResetDuringABackoffReloadsOnce() async throws {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine, retry: makeRetryLoop(delay: 0.05))
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")], playWhenReady: true)
    engine.hasItem = true
    engine.emit(.failed(URLError(.timedOut), httpStatusCode: nil))
    let retrying = player.retry.pendingTask

    player.mediaServicesWereReset()
    await retrying?.value

    #expect(engine.resolveAndLoadCalls.count == 2)
    #expect(player.retry.pendingTask == nil)
  }

  @Test func aMediaServicesResetReloadsOnANewPlayer() throws {
    let (player, engine, _) = try makePlaying()
    player.mediaServicesWereReset()
    #expect(engine.recreateCount == 1)
    #expect(engine.resolveAndLoadCalls.count == 2)
    #expect(player.playWhenReady)
  }

  @Test func aMediaServicesResetResumesAnOnDemandTrack_notALiveOne() throws {
    let (player, engine, _) = try makePlaying()
    engine.resumePosition = 120
    player.mediaServicesWereReset()
    engine.emit(.itemPrepared(likelyToKeepUp: true))
    #expect(engine.seekCalls == [120])

    let (live, liveEngine, _) = try makePlaying(live: true)
    liveEngine.resumePosition = 120
    live.mediaServicesWereReset()
    liveEngine.emit(.itemPrepared(likelyToKeepUp: true))
    #expect(liveEngine.seekCalls.isEmpty)
  }

  @Test func aMediaServicesResetAfterTheEndOrAStopStartsOver() throws {
    let (ended, endedEngine, _) = try makePlaying()
    endedEngine.resumePosition = 300
    ended.transition(.trackEndedNaturally)
    ended.mediaServicesWereReset()
    #expect(ended.deferredSeek.target == nil)

    let (stopped, stoppedEngine, _) = try makePlaying()
    stopped.stop()
    // The stop's rewind has not landed.
    stoppedEngine.resumePosition = 120
    stopped.mediaServicesWereReset()
    #expect(stopped.deferredSeek.target == nil)
  }

  @Test func aReloadAfterTheEndStartsOver() throws {
    let (player, engine, _) = try makePlaying()
    engine.currentTime = 300
    engine.duration = 300
    engine.resumePosition = 300
    engine.emit(.playedToEnd)
    #expect(player.state == .ended)

    player.reloadResolving(startFromCurrentTime: true)
    #expect(player.deferredSeek.target == nil)
    engine.emit(.itemPrepared(likelyToKeepUp: true))
    #expect(engine.seekCalls.isEmpty)
  }

  @Test func aReloadAfterAStopStartsOver() throws {
    let (player, engine, _) = try makePlaying()
    engine.seekResult = nil
    player.stop()
    // The stop's rewind has not landed.
    engine.resumePosition = 120
    player.reloadResolving(startFromCurrentTime: true)
    #expect(player.deferredSeek.target == nil)
  }

  @Test func aStopRewindsAFiniteTrack_notALiveOne() throws {
    let (player, engine, _) = try makePlaying()
    player.stop()
    #expect(engine.seekCalls == [0])

    let (live, liveEngine, _) = try makePlaying(live: true)
    live.stop()
    #expect(liveEngine.seekCalls.isEmpty)
  }

  @Test func aNonPositiveRateIsRefused() throws {
    let (player, engine, _) = try makePlaying()
    player.rate = 1.5
    player.rate = 0
    #expect(player.rate == 1.5)
    #expect(engine.rates.map(\.rate) == [1.5])
    #expect(engine.rates.last?.playing == true)
  }

  @Test func isStalledNeedsProgressIntentAndAThinBuffer() throws {
    let (player, engine, _) = try makePlaying()
    engine.currentTime = 10
    engine.isPlaybackLikelyToKeepUp = false
    #expect(player.isStalled)
    // Within Player.stallGraceSeconds.
    engine.currentTime = 0.2
    #expect(!player.isStalled)
  }
}

// Serialized: every test shares makePlayer's "PlayerTests" defaults suite.
@Suite("Player - review regressions", .serialized)
@MainActor
struct PlayerReviewRegressionTests {
  @Test func aRestartFromAnErrorDuringTheStartSeekKeepsTheStart() throws {
    UserDefaults(suiteName: "PlayerTests")!.removePersistentDomain(forName: "PlayerTests")
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine)
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")], startPositionMs: 25000)
    engine.seekResult = nil
    engine.hasItem = true
    engine.emit(.itemPrepared(likelyToKeepUp: true))
    // The failed item's own time.
    engine.resumePosition = 0
    engine.emit(.unplayable)
    #expect(player.state == .error)
    let saved = PlaybackPersistence(defaults: UserDefaults(suiteName: "PlayerTests")!) { nil }.load()
    #expect(saved?.positionMs == 25000)

    player.play()
    engine.emit(.itemPrepared(likelyToKeepUp: true))

    #expect(engine.seekCalls == [25, 25])
  }

  @Test func aSeekInAnErrorDuringTheStartSeekReplacesTheStart() throws {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine)
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")], startPositionMs: 25000)
    engine.seekResult = nil
    engine.hasItem = true
    engine.emit(.itemPrepared(likelyToKeepUp: true))
    engine.emit(.failed(nil, httpStatusCode: 404))
    #expect(player.state == .error)

    player.seek(to: 40)
    player.seek(by: 5)
    engine.completeSeeks(false)
    player.play()
    engine.emit(.itemPrepared(likelyToKeepUp: true))

    #expect(engine.seekCalls == [25, 45])
  }

  @Test func aSeekInAnErrorIsWhereThePlayResumes() throws {
    let (player, engine, _) = try makePlaying()
    engine.resumePosition = 120
    engine.emit(.failed(nil, httpStatusCode: 404))
    #expect(player.state == .error)

    player.seek(by: -80)
    player.play()
    engine.emit(.itemPrepared(likelyToKeepUp: true))

    #expect(engine.seekCalls == [40])
  }

  @Test func endingWithTheIntentAlreadyOffSavesPositionZero() throws {
    UserDefaults(suiteName: "PlayerTests")!.removePersistentDomain(forName: "PlayerTests")
    let (player, engine, _) = try makePlaying()
    engine.currentTime = 300
    engine.duration = 300
    player.pause()
    // An end-of-track sleep timer drops the intent while still .playing; without the save on
    // entering .ended, a cold resume seeks to the end and re-ends.
    player.transition(.trackEndedNaturally)
    let saved = PlaybackPersistence(defaults: UserDefaults(suiteName: "PlayerTests")!) { nil }.load()
    #expect(saved?.positionMs == 0)
  }

  @Test func skippingWhilePausedSavesTheNewTrackAtItsStart() throws {
    UserDefaults(suiteName: "PlayerTests")!.removePersistentDomain(forName: "PlayerTests")
    let (player, engine, _) = try makePlaying()
    try player.add([Track(id: "t2", src: "https://example.com/b.mp3", title: "B")])
    player.pause()
    // The engine still reports the old item's position until the new item is prepared.
    engine.currentTime = 1800
    player.next()
    let saved = PlaybackPersistence(defaults: UserDefaults(suiteName: "PlayerTests")!) { nil }.load()
    #expect(saved?.track.id == "t2")
    #expect(saved?.positionMs == 0)
  }

  @Test func aRetryWhilePausedSavesWhereItWas() throws {
    UserDefaults(suiteName: "PlayerTests")!.removePersistentDomain(forName: "PlayerTests")
    let (player, engine, _) = try makePlaying()
    player.pause()
    engine.currentTime = 120
    engine.resumePosition = 120
    // The reload holds its resume position before the loading transition saves the snapshot.
    player.reloadResolving(startFromCurrentTime: true)
    let saved = PlaybackPersistence(defaults: UserDefaults(suiteName: "PlayerTests")!) { nil }.load()
    #expect(saved?.positionMs == 120_000)
    #expect(player.deferredSeek.target == 120)
  }

  @Test func aFailedSkipKeepsTheNewTrackAtItsStart() throws {
    UserDefaults(suiteName: "PlayerTests")!.removePersistentDomain(forName: "PlayerTests")
    let (player, engine, _) = try makePlaying()
    try player.add([Track(id: "t2", src: "https://example.com/b.mp3", title: "B")])
    engine.currentTime = 1800
    engine.resumePosition = 1800
    player.next()
    // The failed load leaves the previous item, and its position, in the engine.
    engine.emit(.unplayable)
    #expect(player.state == .error)
    let saved = PlaybackPersistence(defaults: UserDefaults(suiteName: "PlayerTests")!) { nil }.load()
    #expect(saved?.track.id == "t2")
    #expect(saved?.positionMs == 0)

    player.play()
    #expect(player.state == .loading)
    #expect(player.deferredSeek.target == nil)
  }

  @Test func aStartPositionIsSavedWithTheTrackChange() throws {
    UserDefaults(suiteName: "PlayerTests")!.removePersistentDomain(forName: "PlayerTests")
    let player = makePlayer()
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "A")], startPositionMs: 30000)
    let saved = PlaybackPersistence(defaults: UserDefaults(suiteName: "PlayerTests")!) { nil }.load()
    #expect(saved?.positionMs == 30000)
    #expect(player.deferredSeek.target == 30)
  }

  @Test func playOnATrackWithoutASourceFailsInsteadOfReloading() throws {
    let (player, engine, _) = try makePlaying()
    try player.setQueue([Track(id: "t2", path: "/no-source", src: nil, title: "No source")])
    #expect(player.state == .error)
    let resolves = engine.resolveAndLoadCalls.count

    // The media loader's last URL belongs to the earlier track: play must not reload it.
    player.play()

    #expect(player.state == .error)
    #expect(player.nitroPlaybackError?.code == TrackPlayerError.PlaybackError.invalidSourceUrl("nil").code)
    #expect(engine.resolveAndLoadCalls.count == resolves)
  }

  @Test func anErrorWhilePausedSavesWhereItStopped() throws {
    UserDefaults(suiteName: "PlayerTests")!.removePersistentDomain(forName: "PlayerTests")
    let (player, engine, _) = try makePlaying()
    player.pause()
    engine.currentTime = 42
    // Nothing plays, so no periodic save runs: the transition alone rewrites the snapshot.
    engine.emit(.failed(nil, httpStatusCode: nil))
    #expect(player.state == .error)
    let saved = PlaybackPersistence(defaults: UserDefaults(suiteName: "PlayerTests")!) { nil }.load()
    #expect(saved?.positionMs == 42000)
  }

  @Test func playingSavesPeriodically() async throws {
    UserDefaults(suiteName: "PlayerTests")!.removePersistentDomain(forName: "PlayerTests")
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine, saveInterval: .milliseconds(10))
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")], playWhenReady: true)
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

  @Test func aPersistedSessionResumesWhereItWasSaved() throws {
    let defaults = UserDefaults(suiteName: "PlayerTests")!
    defaults.removePersistentDomain(forName: "PlayerTests")
    let saved = PersistedPlaybackState(
      track: JsonTrack(from: Track(id: "t1", src: "https://example.com/a.mp3", title: "A")),
      positionMs: 30000, repeatMode: "queue", shuffleEnabled: false, playbackSpeed: 1.5,
    )
    PlaybackPersistence(defaults: defaults) { saved }.save()

    let player = makePlayer()
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
    #expect(makePlayer().persistedSession() == nil)
  }

  @Test func aTrackChangeClearsTheRetryingErrorOfTheTrackItLeaves() async throws {
    let (player, engine, callbacks) = try makeLoading()
    player.setPlayWhenReady(true)
    engine.emit(.loadFailed(URLError(.timedOut)))
    #expect(callbacks.playbackChanges.last?.error != nil)

    try player.setQueue([Track(id: "t2", src: "https://example.com/b.mp3", title: "B")])

    #expect(callbacks.playbackChanges.last?.state == .loading)
    #expect(callbacks.playbackChanges.last?.error == nil)
    player.retry.cancel()
  }

  @Test func skippingAwayFromAnErrorNeverPublishesAnErrorlessError() throws {
    let (player, engine, callbacks) = try makeLoading()
    engine.emit(.unplayable)
    callbacks.playbackChanges.removeAll()
    callbacks.errorEvents.removeAll()
    try player.setQueue([Track(id: "t2", src: "https://example.com/b.mp3", title: "B")])
    #expect(!callbacks.playbackChanges.contains { $0.state == .error })
    #expect(callbacks.errorEvents.count == 1)
  }
}

@Suite("Player - pausing a live stream")
@MainActor
struct PlayerLivePauseTests {
  @Test func aLivePauseClosesTheConnection() throws {
    let (player, engine, _) = try makePlaying(live: true)
    let carriers = engine.carrierCount
    player.pause()
    #expect(engine.carrierCount == carriers + 1)
    #expect(player.state == .paused)
    #expect(player.load?.reloadOnPlay == true)
  }

  /// A skip back from the lock screen while paused, then a play.
  @Test func aPlayAfterALivePauseReconnectsAtTheLiveEdge() throws {
    let (player, engine, _) = try makePlaying(live: true)
    player.pause()
    engine.resumePosition = 120
    player.seek(by: -15)
    player.play()
    #expect(engine.resolveAndLoadCalls.count == 2)
    #expect(player.state == .loading)

    engine.hasItem = true
    engine.hasLoadedAsset = true
    engine.emit(.itemPrepared(likelyToKeepUp: true))
    #expect(engine.seekCalls.isEmpty)
  }

  /// A live HLS stream with a DVR window, playing 2 minutes behind its edge.
  @MainActor private func makePlayingSeekableLive(window: Double = 600) throws -> (Player, MockPlayerEngine) {
    let (player, engine, _) = try makePlaying(live: true)
    engine.liveWindowSeconds = window
    engine.liveEdge = 1000
    engine.currentTime = 880
    engine.resumePosition = 880
    return (player, engine)
  }

  @Test func aSeekableLivePauseKeepsItsPositionAndResumesFromIt() throws {
    let (player, engine) = try makePlayingSeekableLive()
    let plays = engine.startPlaybackCallCount
    player.pause()
    engine.emit(.timeControlStatus(.paused, waitingForNoItem: false))
    #expect(engine.carrierCount == 0)
    #expect(player.state == .paused)
    #expect(player.load?.reloadOnPlay == false)
    #expect(engine.currentTime == 880)

    player.play()
    #expect(engine.resolveAndLoadCalls.count == 1)
    #expect(engine.startPlaybackCallCount == plays + 1)
    #expect(engine.seekCalls.isEmpty)
  }

  @Test func aJumpToTheLiveEdgeWhileASeekableLiveTrackIsPausedSeeksToTheWindowEnd() throws {
    let (player, engine) = try makePlayingSeekableLive()
    player.pause()
    player.seekToLiveEdge()
    #expect(engine.seekCalls == [1000])
    #expect(engine.resolveAndLoadCalls.count == 1)
  }

  @Test func aSeekableLiveStopKeepsItsItem() throws {
    let (player, engine) = try makePlayingSeekableLive()
    player.stop()
    #expect(engine.carrierCount == 0)
    #expect(player.state == .stopped)
    player.seekToLiveEdge()
    #expect(engine.seekCalls == [1000])
  }

  @Test func aLiveWindowShorterThanAPauseIsWorthClosesLikeAStreamWithNone() throws {
    let (player, engine) = try makePlayingSeekableLive(window: Player.pausableLiveWindowSeconds - 1)
    player.pause()
    #expect(engine.carrierCount == 1)
    #expect(player.load?.reloadOnPlay == true)
  }

  @Test func anOnDemandPauseKeepsTheItem() throws {
    let (player, engine, _) = try makePlaying()
    player.pause()
    engine.emit(.timeControlStatus(.paused, waitingForNoItem: false))
    #expect(engine.carrierCount == 0)
    #expect(player.state == .paused)
    #expect(player.load?.reloadOnPlay == false)
  }

  @Test func aLiveStopClosesTheConnectionWithoutPassingThroughPaused() throws {
    let (player, engine, callbacks) = try makePlaying(live: true)
    let carriers = engine.carrierCount
    callbacks.playbackChanges.removeAll()
    player.stop()
    #expect(engine.carrierCount == carriers + 1)
    #expect(callbacks.playbackChanges.map(\.state) == [.stopped])
    player.play()
    #expect(engine.resolveAndLoadCalls.count == 2)
  }

  @Test func aLiveStreamLoadedWithoutIntentOpensNoConnection() throws {
    let scheduler = ManualSleepTimerScheduler()
    let (player, engine, _) = try makeLoading(live: true, scheduler: scheduler)
    #expect(engine.resolveAndLoadCalls.isEmpty)
    #expect(engine.carrierCount == 0)
    #expect(player.state == .loading)

    scheduler.advance(to: Player.idleLiveParkDelaySeconds)
    #expect(engine.resolveAndLoadCalls.isEmpty)
    #expect(engine.carrierCount == 1)
    #expect(player.state == .paused)
    player.play()
    #expect(engine.resolveAndLoadCalls.count == 1)
  }

  /// A station started from paused as two calls, the way a consumer without `playWhenReady` does.
  @Test func aLiveStreamQueuedThenPlayedFromPausedNeverReportsPaused() throws {
    let scheduler = ManualSleepTimerScheduler()
    let (player, engine, callbacks) = try makePlaying(live: true, scheduler: scheduler)
    player.pause()
    let carriers = engine.carrierCount
    callbacks.playbackChanges.removeAll()

    try player.setQueue([Track(id: "t2", src: "https://example.com/b.mp3", title: "B", live: true)])
    player.play()
    scheduler.advance(to: Player.idleLiveParkDelaySeconds)
    #expect(engine.resolveAndLoadCalls.count == 2)
    #expect(engine.carrierCount == carriers)
    #expect(!callbacks.playbackChanges.contains { $0.state == .paused })
    #expect(player.state == .loading)
  }

  @Test func aPlayArriving100msAfterTheQueueNeverReportsPaused() async throws {
    let (player, engine, callbacks) = try makePlaying(live: true, scheduler: MainQueueSleepTimerScheduler())
    player.pause()
    let carriers = engine.carrierCount
    callbacks.playbackChanges.removeAll()

    try player.setQueue([Track(id: "t2", src: "https://example.com/b.mp3", title: "B", live: true)])
    try await Task.sleep(for: .milliseconds(100))
    player.play()
    try await Task.sleep(for: .seconds(Player.idleLiveParkDelaySeconds + 0.5))
    #expect(engine.resolveAndLoadCalls.count == 2)
    #expect(engine.carrierCount == carriers)
    #expect(!callbacks.playbackChanges.contains { $0.state == .paused })
    #expect(player.state == .loading)
  }

  @Test func withNoPlayTheDeferredParkFiresOnTheMainQueue() async throws {
    let (player, engine, _) = try makeLoading(live: true, scheduler: MainQueueSleepTimerScheduler())
    try await Task.sleep(for: .seconds(Player.idleLiveParkDelaySeconds + 0.5))
    #expect(engine.carrierCount == 1)
    #expect(player.state == .paused)
  }

  @Test func aStopDuringTheDeferredParkClosesTheItemAndStaysStopped() throws {
    let scheduler = ManualSleepTimerScheduler()
    let (player, engine, _) = try makeLoading(live: true, scheduler: scheduler)
    player.stop()
    #expect(engine.carrierCount == 1)
    scheduler.advance(to: Player.idleLiveParkDelaySeconds)
    #expect(player.state == .stopped)
    #expect(engine.carrierCount == 1)
  }

  @Test func aPauseDuringTheDeferredParkStillParksAtTheDeadline() throws {
    let scheduler = ManualSleepTimerScheduler()
    let (player, engine, _) = try makeLoading(live: true, scheduler: scheduler)
    player.pause()
    #expect(player.state == .loading)
    scheduler.advance(to: Player.idleLiveParkDelaySeconds)
    #expect(player.state == .paused)
    #expect(engine.resolveAndLoadCalls.isEmpty)
  }

  @Test func aPlayPauseAndPlayInsideTheDelayIsNotParkedByIt() throws {
    let scheduler = ManualSleepTimerScheduler()
    let (player, engine, _) = try makeLoading(live: true, scheduler: scheduler)
    player.play()
    player.pause()
    player.play()
    let carriers = engine.carrierCount
    scheduler.advance(to: Player.idleLiveParkDelaySeconds)
    #expect(engine.carrierCount == carriers)
    #expect(player.state == .loading)
    #expect(engine.resolveAndLoadCalls.count == 2)
  }

  @Test func aLiveStreamQueuedToPlayConnectsWithoutParking() throws {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine)
    let callbacks = MockPlayerCallbacks()
    player.callbacks = callbacks
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T", live: true)], playWhenReady: true)
    #expect(engine.carrierCount == 0)
    #expect(engine.resolveAndLoadCalls.count == 1)
    #expect(!callbacks.playbackChanges.contains { $0.state == .paused })
  }

  @Test func skippingToALiveStreamWhilePausedDoesNotConnect() throws {
    let scheduler = ManualSleepTimerScheduler()
    let (player, engine, _) = try makePlaying(scheduler: scheduler)
    try player.add([Track(id: "t2", src: "https://example.com/b.mp3", title: "B", live: true)])
    player.pause()
    player.next()
    scheduler.advance(to: Player.idleLiveParkDelaySeconds)
    #expect(engine.resolveAndLoadCalls.count == 1)
    #expect(player.state == .paused)
  }

  @Test func aMediaServicesResetWhileParkedDoesNotConnect() throws {
    let scheduler = ManualSleepTimerScheduler()
    let (player, engine, _) = try makePlaying(live: true, scheduler: scheduler)
    player.pause()
    player.mediaServicesWereReset()
    scheduler.advance(to: Player.idleLiveParkDelaySeconds)
    #expect(engine.resolveAndLoadCalls.count == 1)
    #expect(player.state == .paused)
  }

  /// The carrier item has no asset: the AVPlayer reports a pause without one.
  @Test func thePausedStatusOfTheEmptiedPlayerKeepsItPaused() throws {
    let (player, engine, _) = try makePlaying(live: true)
    player.pause()
    engine.emit(.timeControlStatus(.paused, waitingForNoItem: false))
    #expect(player.state == .paused)
  }

  /// The dropped item's first audio, reported after the pause that dropped it.
  @Test func audioReportedAfterTheDropKeepsItPaused() throws {
    let (player, engine, _) = try makePlaying(live: true)
    player.pause()
    engine.emit(.audioDidStart)
    #expect(player.state == .paused)
  }

  @Test func aLivePauseDuringAConnectClosesIt() async throws {
    let (player, engine, clock) = try makeConnecting()
    await clock.run(until: 10_000)
    player.pause()
    await clock.run(until: 200_000)
    #expect(engine.carrierCount == 1)
    #expect(engine.resolveAndLoadCalls.count == 1)
    #expect(player.state == .paused)
  }

  @Test func aLivePauseDuringAStallLeavesNoReconnect() async throws {
    let (player, engine, _, clock) = try makeVirtual()
    player.transition(.avPlayerPlaying)
    await clock.run(until: 10_000)
    player.transition(.avPlayerWaiting)
    #expect(player.retry.stallTask != nil)

    player.pause()
    #expect(player.retry.stallTask == nil)
    #expect(player.retry.pendingTask == nil)
    await clock.run(until: 600_000)
    #expect(engine.resolveAndLoadCalls.count == 1)
    #expect(player.state == .paused)
  }

  @Test func aLivePauseAfterTheRetryGaveUpClosesTheConnection() throws {
    let (player, engine, _) = try makePlaying(live: true)
    player.transition(.errorOccurred(.playbackStalled))
    let carriers = engine.carrierCount
    player.pause()
    #expect(engine.carrierCount == carriers + 1)
    #expect(player.state == .error)
    player.play()
    #expect(engine.resolveAndLoadCalls.count == 2)
  }

  @Test func aJumpToTheLiveEdgeAfterAStopStaysStopped() throws {
    let (player, engine, _) = try makePlaying(live: true)
    player.stop()
    player.seekToLiveEdge()
    player.retryNow()
    #expect(player.state == .stopped)
    #expect(engine.resolveAndLoadCalls.count == 1)
  }

  /// Paused longer than any silence the loop tolerates: the reconnect gets a whole budget.
  @Test func aPlayAfterAPauseLongerThanTheBudgetReconnectsWithoutError() async throws {
    let (player, engine, callbacks, clock) = try makeVirtual()
    player.transition(.avPlayerPlaying)
    await clock.run(until: 10_000)
    player.pause()
    await clock.run(until: 610_000)
    player.play()
    await clock.run(until: 615_000)
    #expect(engine.resolveAndLoadCalls.count == 2)
    #expect(player.state == .loading)
    #expect(callbacks.errorEvents.isEmpty)
  }
}
