@testable import AudioBrowserTestable
import Foundation
import Testing

@MainActor
private func makeLoading(
  src: String? = "https://example.com/a.mp3",
  live: Bool = false,
) throws -> (Player, MockPlayerEngine, MockPlayerCallbacks) {
  let engine = MockPlayerEngine()
  let player = makePlayer(engine: engine, retries: false)
  let callbacks = MockPlayerCallbacks()
  player.callbacks = callbacks
  try player.setQueue([Track(id: "t1", src: src, title: "T", live: live)])
  return (player, engine, callbacks)
}

@MainActor
private func makePlaying(live: Bool = false) throws -> (Player, MockPlayerEngine, MockPlayerCallbacks) {
  let (player, engine, callbacks) = try makeLoading(live: live)
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
  let player = makePlayer(engine: engine, retry: makeRetryLoop(givesUp: givesUp), retries: nil)
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
    let player = makePlayer(engine: engine, retries: false)
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
    let player = makePlayer(engine: engine, retries: false)
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
    let player = makePlayer(engine: engine, retries: false)
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
    let player = makePlayer(engine: engine, retries: false)
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")], startPositionMs: 25000)

    player.mediaServicesWereReset()
    engine.hasItem = true
    engine.emit(.itemPrepared(likelyToKeepUp: true))

    #expect(engine.seekCalls == [25])
  }

  @Test func aPlayRightAfterAStopDuringTheStartSeekStartsOver() throws {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine, retries: false)
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
    let player = makePlayer(engine: engine, retries: false)
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.m3u8", title: "T", live: true)], startPositionMs: 25000)
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
    player.retry.shouldRetry = { true }
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
    live.retry.shouldRetry = { true }
    liveEngine.emit(.playedToEnd)
    await live.retry.pendingTask?.value
    #expect(live.state == .loading)
    #expect(liveEngine.resolveAndLoadCalls.count == 2)
    #expect(live.retry.attempts == 1)
  }

  /// AVPlayer pauses an item at its end, so a seek within its window would leave it silent.
  @Test func aLiveEndReconnectsEvenWithASeekableWindow() async throws {
    let (player, engine, _) = try makePlaying(live: true)
    player.retry.shouldRetry = { true }
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
    player.retry.shouldRetry = { true }
    for _ in 0 ..< 20 where player.state != .error {
      engine.emit(.playedToEnd)
      await player.retry.pendingTask?.value
      engine.emit(.itemPrepared(likelyToKeepUp: true))
    }
    #expect(engine.resolveAndLoadCalls.count == 4)
    #expect(player.state == .error)
    #expect(player.errorStatus.error == .playbackStalled)
    #expect(callbacks.errorEvents.count == 1)
  }

  /// Each reconnect plays long enough to refill the budget, as a stream closing once an hour does.
  @Test func liveEndsFarApartNeverSpendTheBudget() async throws {
    let (player, engine, callbacks) = try makePlaying(live: true)
    player.retry.updatePolicy(from: .second(RetryConfig(
      maxRetries: 1, maxRetryDurationMs: nil, firstConnectMaxRetryDurationMs: nil,
    )))
    player.retry.shouldRetry = { true }
    player.retry.healthyPlaybackDuration = .milliseconds(10)
    for _ in 0 ..< 5 {
      engine.emit(.playedToEnd)
      await player.retry.pendingTask?.value
      #expect(player.retry.attempts == 1)
      engine.emit(.itemPrepared(likelyToKeepUp: true))
      player.transition(.avPlayerPlaying)
      await player.retry.healthyPlaybackTask?.value
      #expect(player.retry.attempts == 0)
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
    player.retry.shouldRetry = { true }
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
    let (player, _, callbacks) = try makeStalling(givesUp: true)
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
    try player.setQueue([Track(id: "t2", src: "https://example.com/b.mp3", title: "B")])
    #expect(player.retry.stallTask == nil)
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

  @Test func theNetworkReturningReconnectsAStalledStream_throughTheRetryLoop() async throws {
    let (player, engine, _) = try makePlaying()
    player.retry.shouldRetry = { true }
    player.transition(.avPlayerWaiting)
    #expect(player.state == .buffering)
    player.networkStatusDidChange(isOnline: true)
    await player.retry.pendingTask?.value
    #expect(engine.resolveAndLoadCalls.count == 2)
    #expect(player.retry.attempts == 1)
  }

  @Test func aFlappingConnectionEndsInATerminalErrorOnceTheBudgetIsSpent() async throws {
    let (player, engine, callbacks) = try makePlaying()
    player.retry.updatePolicy(from: .second(RetryConfig(
      maxRetries: 2, maxRetryDurationMs: nil, firstConnectMaxRetryDurationMs: nil,
    )))
    player.retry.shouldRetry = { true }
    for flap in 1 ... 3 {
      player.transition(.avPlayerWaiting)
      player.networkStatusDidChange(isOnline: true)
      await player.retry.pendingTask?.value
      if flap == 3 { break }
      engine.emit(.itemPrepared(likelyToKeepUp: true))
      player.transition(.avPlayerPlaying)
    }
    #expect(engine.resolveAndLoadCalls.count == 3)
    #expect(player.state == .error)
    #expect(callbacks.errorEvents.count == 1)
  }

  @Test func theNetworkReturningWithRetryOffLeavesTheStallToAVPlayer() throws {
    let (player, engine, callbacks) = try makePlaying()
    player.retry.updatePolicy(from: nil)
    player.transition(.avPlayerWaiting)
    player.networkStatusDidChange(isOnline: true)
    #expect(player.state == .buffering)
    #expect(engine.resolveAndLoadCalls.count == 1)
    #expect(callbacks.errorEvents.isEmpty)
  }

  @Test func theNetworkReturningLeavesAPausedOrPlayingStreamAlone() throws {
    let (player, engine, _) = try makePlaying()
    player.networkStatusDidChange(isOnline: true)
    player.pause()
    player.networkStatusDidChange(isOnline: true)
    #expect(engine.resolveAndLoadCalls.count == 1)
  }

  @Test func theNetworkReturningDuringABackoffLeavesTheReloadToTheRetry() async throws {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine, retry: makeRetryLoop(delay: 0.05), retries: nil)
    try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")])
    player.play()
    player.transition(.avPlayerWaiting)
    engine.emit(.failed(URLError(.timedOut), httpStatusCode: nil))
    let retrying = player.retry.pendingTask
    let loadsBefore = engine.resolveAndLoadCalls.count

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
    player.retry.shouldRetry = { true }
    player.transition(.avPlayerWaiting)
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
    #expect(first?.hasPlayed == true)

    engine.emit(.unplayable)
    #expect(player.state == .error)
    player.play()

    #expect(player.load?.id != first?.id)
    #expect(player.load?.hasPlayed == false)
    #expect(player.load?.primedNowPlaying == false)
    #expect(engine.recreateCount == 1)
  }

  @Test func aRetryKeepsItsLoad() async throws {
    let (player, engine, _) = try makePlaying()
    player.retry.shouldRetry = { true }
    let first = player.load
    engine.emit(.failed(URLError(.timedOut), httpStatusCode: nil))
    await player.retry.pendingTask?.value
    #expect(player.load?.id == first?.id)
    #expect(engine.recreateCount == 0)
  }

  @Test func aRetryAfterTheAVPlayerItselfFailedReloadsOnANewOne_inTheSameLoad() async throws {
    let (player, engine, _) = try makePlaying()
    player.retry.shouldRetry = { true }
    engine.emit(.audioDidStart)
    let first = player.load
    engine.playerFailed = true

    engine.emit(.failed(URLError(.networkConnectionLost), httpStatusCode: nil))
    await player.retry.pendingTask?.value

    #expect(engine.recreateCount == 1)
    #expect(engine.resolveAndLoadCalls.count == 2)
    #expect(player.load?.id == first?.id)
    #expect(player.load?.hasPlayed == true)
  }

  @Test func aMediaServicesResetDuringABackoffReloadsOnce() async throws {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine, retry: makeRetryLoop(delay: 0.05), retries: nil)
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
    let player = makePlayer(engine: engine, retries: false)
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
    let player = makePlayer(engine: engine, retries: false)
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
    let player = makePlayer(retries: false)
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
    let player = makePlayer(engine: engine, retries: false, saveInterval: .milliseconds(10))
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

  @Test func aTrackChangeClearsTheRetryingErrorOfTheTrackItLeaves() async throws {
    let (player, engine, callbacks) = try makeLoading()
    player.retry.shouldRetry = { true }
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
