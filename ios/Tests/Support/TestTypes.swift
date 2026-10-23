@testable import AudioBrowserTestable
import AVFoundation

/// Creates an AVMetadataItem with the specified properties for testing.
func makeMetadataItem(
  identifier: AVMetadataIdentifier? = nil,
  commonKey: AVMetadataKey? = nil,
  keySpace: AVMetadataKeySpace? = nil,
  key: String? = nil,
  value: String,
) -> AVMetadataItem {
  let item = AVMutableMetadataItem()
  if let identifier {
    item.identifier = identifier
  }
  if let commonKey {
    item.key = commonKey as NSString
    item.keySpace = keySpace ?? .common
  }
  if let key, let keySpace {
    item.key = key as NSString
    item.keySpace = keySpace
  }
  item.value = value as NSString
  return item
}

// MARK: - Mock PlayerEngine

@MainActor
final class MockPlayerEngine: PlayerEngine {
  var onEvent: (@MainActor (EngineEvent) -> Void)?

  /// Reports `event` as the media stack would.
  func emit(_ event: EngineEvent) {
    onEvent?(event)
  }

  // The current item
  var hasItem = false
  var hasLoadedAsset = false
  var currentTime: Double = 0
  var duration: Double = 0
  var bufferedPosition: Double = 0
  var resumePosition: Double?
  var liveEdge: Double?
  var isPlaybackLikelyToKeepUp = false
  var isAirPlayRoute = false
  var volume: Float = 1

  // Loading
  var resolveAndLoadCalls: [String] = []
  var cancelLoadingCount = 0
  var dropAssetCount = 0
  var clearItemCount = 0
  var stopObservingItemCount = 0
  var recreateCount = 0

  func resolveAndLoad(src: String, track _: Track) { resolveAndLoadCalls.append(src) }
  func cancelLoading() { cancelLoadingCount += 1 }
  func dropAsset() { dropAssetCount += 1 }
  func clearItem() { clearItemCount += 1 }
  func stopObservingItem() { stopObservingItemCount += 1 }
  func recreate() { recreateCount += 1 }

  // Transport
  var startPlaybackCallCount = 0
  var pausePlaybackCallCount = 0
  var setTimePitchCallCount = 0
  var rates: [(rate: Float, playing: Bool)] = []
  var seekCalls: [Double] = []
  /// What a seek completes with; nil leaves it in flight until `completeSeeks`.
  var seekResult: Bool? = true

  func play() { startPlaybackCallCount += 1 }
  func pause() { pausePlaybackCallCount += 1 }
  func setRate(_ rate: Float, playing: Bool) { rates.append((rate, playing)) }
  private var seeksInFlight: [@MainActor (Bool) -> Void] = []

  func seek(to seconds: Double, completion: @escaping @MainActor (Bool) -> Void) {
    seekCalls.append(seconds)
    if let seekResult { completion(seekResult) } else { seeksInFlight.append(completion) }
  }

  /// Lands the seeks left in flight while `seekResult` was nil.
  func completeSeeks(_ finished: Bool) {
    let pending = seeksInFlight
    seeksInFlight = []
    for completion in pending {
      completion(finished)
    }
  }

  func applyTimePitchAlgorithm() { setTimePitchCallCount += 1 }

  // Now playing and remote commands
  var primeCount = 0
  var loadNowPlayingMetadataCalls: [Track] = []
  var clearNowPlayingCallCount = 0
  var updateRemoteRepeatModeCalls: [RepeatMode] = []
  var updateRemoteShuffleModeCalls: [Bool] = []
  var updateSkipAvailabilityCalls: [(canNext: Bool, canPrevious: Bool)] = []

  func primeNowPlaying() { primeCount += 1 }
  func loadNowPlayingMetadata(for track: Track) { loadNowPlayingMetadataCalls.append(track) }
  func clearNowPlaying() { clearNowPlayingCallCount += 1 }
  func updateRemoteRepeatMode(_ mode: RepeatMode) { updateRemoteRepeatModeCalls.append(mode) }
  func updateRemoteShuffleMode(_ enabled: Bool) { updateRemoteShuffleModeCalls.append(enabled) }
  func updateSkipAvailability(canNext: Bool, canPrevious: Bool) {
    updateSkipAvailabilityCalls.append((canNext, canPrevious))
  }
}

/// A Player over `engine` whose retries reload while `retries` and otherwise give up, keeping
/// its resume snapshot out of the app's defaults.
@MainActor
func makePlayer(
  engine: MockPlayerEngine = MockPlayerEngine(),
  retry: RetryLoop = makeRetryLoop(),
  retries: Bool = true,
  sleepTimer: SleepTimerManager = SleepTimerManager(scheduler: ManualSleepTimerScheduler()),
  saveInterval: Duration = .seconds(5),
) -> Player {
  let player = Player(
    engine: engine, retry: retry, sleepTimerManager: sleepTimer,
    persistenceDefaults: UserDefaults(suiteName: "PlayerTests")!, persistenceSaveInterval: saveInterval,
  )
  retry.shouldRetry = { retries }
  return player
}

// MARK: - Mock PlayerCallbacks

@MainActor
final class MockPlayerCallbacks: PlayerCallbacks {
  var playbackChanges: [Playback] = []
  var activeTrackChanges: [PlaybackActiveTrackChangedEvent] = []
  var progressUpdates: [PlaybackProgressUpdatedEvent] = []
  var playWhenReadyChanges: [Bool] = []
  var playingStateChanges: [PlayingState] = []
  var queueEndedEvents: [PlaybackQueueEndedEvent] = []
  var queueChanges: [[Track]] = []
  /// The order in which the queue and active-track callbacks arrived.
  var queueAndTrackOrder: [String] = []
  var repeatModeChanges: [RepeatModeChangedEvent] = []
  var shuffleEnabledChanges: [Bool] = []
  var errorEvents: [PlaybackErrorEvent] = []
  var playbackIntervalFiredCount = 0
  var releaseSessionCount = 0

  /// The order playback, intent and playing-state changes arrived in.
  var publishOrder: [String] = []

  func playerDidChangePlayback(_ playback: Playback) {
    publishOrder.append("playback")
    playbackChanges.append(playback)
  }

  func playerDidChangeActiveTrack(_ event: PlaybackActiveTrackChangedEvent) {
    activeTrackChanges.append(event)
    queueAndTrackOrder.append("activeTrack")
  }

  func playerDidUpdateProgress(_ event: PlaybackProgressUpdatedEvent) {
    progressUpdates.append(event)
  }

  func playerDidFirePlaybackInterval() {
    playbackIntervalFiredCount += 1
  }

  func playerDidChangePlayWhenReady(_ playWhenReady: Bool) {
    publishOrder.append("playWhenReady")
    playWhenReadyChanges.append(playWhenReady)
  }

  func playerDidChangePlayingState(_ state: PlayingState) {
    publishOrder.append("playingState")
    playingStateChanges.append(state)
  }

  func playerDidEndQueue(_ event: PlaybackQueueEndedEvent) {
    queueEndedEvents.append(event)
  }

  func playerDidChangeQueue(_ tracks: [Track]) {
    queueChanges.append(tracks)
    queueAndTrackOrder.append("queue")
  }

  func playerDidChangeRepeatMode(_ event: RepeatModeChangedEvent) {
    repeatModeChanges.append(event)
  }

  func playerDidChangeShuffleEnabled(_ enabled: Bool) {
    shuffleEnabledChanges.append(enabled)
  }

  func playerDidError(_ event: PlaybackErrorEvent) {
    errorEvents.append(event)
  }

  func playerShouldReleaseSession() {
    releaseSessionCount += 1
  }

  var sleepTimerChanges: [SleepTimerState?] = []
  func playerDidChangeSleepTimer(_ state: SleepTimerState?) {
    sleepTimerChanges.append(state)
  }
}

// MARK: - RetryLoop

/// A RetryLoop for tests: enabled, with an instant backoff unless `delay`, whose attempts reload
/// while `retries` and otherwise give up, as an exhausted budget does.
@MainActor
func makeRetryLoop(retries: Bool = true, delay: TimeInterval = 0) -> RetryLoop {
  let loop = RetryLoop(sleep: { _ in
    if delay > 0 { try await Task.sleep(for: .seconds(delay)) }
  })
  loop.updatePolicy(from: .first(true))
  loop.shouldRetry = { retries }
  return loop
}
