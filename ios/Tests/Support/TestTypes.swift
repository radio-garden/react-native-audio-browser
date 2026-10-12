// This file is intentionally empty — mock types live in
// Model/NitroTypeStubs.swift (part of the AudioBrowserTestable target).

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

// MARK: - Mock PlaybackEffectHandler

@MainActor
final class MockPlaybackEffectHandler: PlaybackEffectHandler {
  // Playback control
  var startPlaybackCallCount = 0
  var pausePlaybackCallCount = 0
  var setTimePitchCallCount = 0

  func startPlayback() { startPlaybackCallCount += 1 }
  func pausePlayback() { pausePlaybackCallCount += 1 }
  func setTimePitchingAlgorithmForCurrentItem() { setTimePitchCallCount += 1 }

  // Player volume
  var volume: Float = 1

  // AVPlayer state queries
  var currentTime: Double = 0
  var duration: Double = 0
  var bufferedPosition: Double = 0
  var hasLoadedAsset: Bool = false

  // Item management
  var clearCurrentItemCallCount = 0
  var stopObservingCurrentItemCallCount = 0

  func clearCurrentItem() { clearCurrentItemCallCount += 1 }
  func stopObservingCurrentItem() { stopObservingCurrentItemCallCount += 1 }

  // Track loading
  var loadTrackCalls: [String] = []
  var loadTrackTracks: [Track] = []
  var reloadTrackCalls: [Bool] = []
  var unloadTrackCallCount = 0
  var cancelMediaLoadingCallCount = 0

  func loadTrack(src: String, track: Track) {
    loadTrackCalls.append(src)
    loadTrackTracks.append(track)
  }

  func reloadTrack(startFromCurrentTime: Bool) { reloadTrackCalls.append(startFromCurrentTime) }
  func unloadTrack() { unloadTrackCallCount += 1 }
  func cancelMediaLoading() { cancelMediaLoadingCallCount += 1 }

  // Seek operations
  var seekToStartCallCount = 0
  var replayCurrentTrackCallCount = 0

  func seekToStart() { seekToStartCallCount += 1 }
  func replayCurrentTrack() { replayCurrentTrackCallCount += 1 }

  // Now Playing
  var loadNowPlayingMetadataCalls: [Track] = []
  var clearNowPlayingCallCount = 0
  var playIntentCalls: [Bool] = []

  func loadNowPlayingMetadata(for track: Track) {
    loadNowPlayingMetadataCalls.append(track)
  }

  func clearNowPlaying() { clearNowPlayingCallCount += 1 }
  func playIntentDidChange(active: Bool) {
    playIntentCalls.append(active)
  }

  // Remote commands
  var updateRemoteRepeatModeCalls: [RepeatMode] = []
  var updateRemoteShuffleModeCalls: [Bool] = []

  func updateRemoteRepeatMode(_ mode: RepeatMode) {
    updateRemoteRepeatModeCalls.append(mode)
  }

  func updateRemoteShuffleMode(_ enabled: Bool) {
    updateRemoteShuffleModeCalls.append(enabled)
  }

  var updateSkipAvailabilityCalls: [(canNext: Bool, canPrevious: Bool)] = []
  func updateSkipAvailability(canNext: Bool, canPrevious: Bool) {
    updateSkipAvailabilityCalls.append((canNext, canPrevious))
  }
}

// MARK: - Mock PlaybackCoordinatorCallbacks

@MainActor
final class MockPlaybackCoordinatorCallbacks: PlaybackCoordinatorCallbacks {
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
