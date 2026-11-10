@testable import AudioBrowserTestable
import AVFoundation

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

  func emit(_ event: EngineEvent) {
    onEvent?(event)
  }

  var hasItem = false
  var hasLoadedAsset = false
  var currentTime: Double = 0
  var duration: Double = 0
  var bufferedPosition: Double = 0
  var resumePosition: Double?
  var liveEdge: Double?
  var isPlaybackLikelyToKeepUp = false
  var isAirPlayRoute = false
  var playerFailed = false
  var volume: Float = 1

  var resolveAndLoadCalls: [String] = []
  var cancelLoadingCount = 0
  var dropAssetCount = 0
  var clearItemCount = 0
  var stopObservingItemCount = 0
  var recreateCount = 0

  func resolveAndLoad(src: String, track _: Track) { resolveAndLoadCalls.append(src) }
  func cancelLoading() { cancelLoadingCount += 1 }
  func dropAsset() { dropAssetCount += 1 }
  func clearItem() {
    clearItemCount += 1
    hasItem = false
    resumePosition = nil
  }

  func stopObservingItem() { stopObservingItemCount += 1 }
  func recreate() {
    recreateCount += 1
    playerFailed = false
  }

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

  func completeSeeks(_ finished: Bool) {
    let pending = seeksInFlight
    seeksInFlight = []
    for completion in pending {
      completion(finished)
    }
  }

  func applyTimePitchAlgorithm() { setTimePitchCallCount += 1 }

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

  var nowPlayingRenderCount = 0
  var nowPlayingTrackChangedCount = 0
  var appliedTimedMetadata: [TimedMetadata] = []
  var activateAudioSessionCount = 0
  var cancelAudioSessionReleaseCount = 0
  var scheduleAudioSessionReleaseCount = 0
  /// Called with the name of each seam call above, so a test can interleave them with callbacks.
  var onSeamCall: ((String) -> Void)?

  func renderNowPlaying() {
    nowPlayingRenderCount += 1
    onSeamCall?("renderNowPlaying")
  }

  func nowPlayingTrackChanged() {
    nowPlayingTrackChangedCount += 1
    onSeamCall?("nowPlayingTrackChanged")
  }

  func applyTimedMetadata(_ metadata: TimedMetadata) {
    appliedTimedMetadata.append(metadata)
    onSeamCall?("applyTimedMetadata")
  }

  func clearTimedMetadata() {
    onSeamCall?("clearTimedMetadata")
  }

  func activateAudioSession() {
    activateAudioSessionCount += 1
    onSeamCall?("activateAudioSession")
  }

  func cancelAudioSessionRelease() {
    cancelAudioSessionReleaseCount += 1
    onSeamCall?("cancelAudioSessionRelease")
  }

  func scheduleAudioSessionRelease() {
    scheduleAudioSessionReleaseCount += 1
    onSeamCall?("scheduleAudioSessionRelease")
  }
}

/// A Player whose retry loop sees a play intent of `retries`, or the real one when `retries` is
/// nil, persisting to a test-only defaults suite. Without a play intent, a retryable failure is
/// left to the next play.
@MainActor
func makePlayer(
  engine: MockPlayerEngine = MockPlayerEngine(),
  retry: RetryLoop = makeRetryLoop(),
  retries: Bool? = true,
  sleepTimerScheduler: any SleepTimerScheduling = ManualSleepTimerScheduler(),
  saveInterval: Duration = .seconds(5),
) -> Player {
  let player = Player(
    engine: engine, retry: retry, sleepTimerScheduler: sleepTimerScheduler,
    persistenceDefaults: UserDefaults(suiteName: "PlayerTests")!, persistenceSaveInterval: saveInterval,
  )
  // Player's init installs its own `shouldRetry`; replace it afterwards.
  if let retries { retry.shouldRetry = { retries } }
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
  var queueAndTrackOrder: [String] = []
  var repeatModeChanges: [RepeatModeChangedEvent] = []
  var shuffleEnabledChanges: [Bool] = []
  var errorEvents: [PlaybackErrorEvent] = []
  var playbackIntervalFiredCount = 0
  var timedMetadata: [TimedMetadata] = []

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

  func playerDidReceiveTimedMetadata(_ metadata: TimedMetadata) {
    publishOrder.append("timedMetadata")
    timedMetadata.append(metadata)
  }

  var sleepTimerChanges: [SleepTimerState?] = []
  func playerDidChangeSleepTimer(_ state: SleepTimerState?) {
    sleepTimerChanges.append(state)
  }
}

// MARK: - RetryLoop

/// An enabled RetryLoop with play intent that backs off for `delay` seconds and reloads, or gives
/// up on the first retry when `givesUp`.
@MainActor
func makeRetryLoop(givesUp: Bool = false, delay: TimeInterval = 0) -> RetryLoop {
  let loop = RetryLoop(sleep: { _ in
    if delay > 0 { try await Task.sleep(for: .seconds(delay)) }
  })
  loop.updatePolicy(from: givesUp ? .second(RetryConfig(
    maxRetries: 0, maxRetryDurationMs: nil, firstConnectMaxRetryDurationMs: nil,
  )) : .first(true))
  loop.shouldRetry = { true }
  return loop
}
