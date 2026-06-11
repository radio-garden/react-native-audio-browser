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
  var updateNowPlayingStateCalls: [Bool] = []

  func loadNowPlayingMetadata(for track: Track) {
    loadNowPlayingMetadataCalls.append(track)
  }

  func clearNowPlaying() { clearNowPlayingCallCount += 1 }
  func updateNowPlayingState(playWhenReady: Bool) {
    updateNowPlayingStateCalls.append(playWhenReady)
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
  var repeatModeChanges: [RepeatModeChangedEvent] = []
  var shuffleEnabledChanges: [Bool] = []
  var errorEvents: [PlaybackErrorEvent] = []
  var playbackIntervalFiredCount = 0

  func playerDidChangePlayback(_ playback: Playback) {
    playbackChanges.append(playback)
  }

  func playerDidChangeActiveTrack(_ event: PlaybackActiveTrackChangedEvent) {
    activeTrackChanges.append(event)
  }

  func playerDidUpdateProgress(_ event: PlaybackProgressUpdatedEvent) {
    progressUpdates.append(event)
  }

  func playerDidFirePlaybackInterval() {
    playbackIntervalFiredCount += 1
  }

  func playerDidChangePlayWhenReady(_ playWhenReady: Bool) {
    playWhenReadyChanges.append(playWhenReady)
  }

  func playerDidChangePlayingState(_ state: PlayingState) {
    playingStateChanges.append(state)
  }

  func playerDidEndQueue(_ event: PlaybackQueueEndedEvent) {
    queueEndedEvents.append(event)
  }

  func playerDidChangeQueue(_ tracks: [Track]) {
    queueChanges.append(tracks)
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
}

// MARK: - Mock SleepTimerHandling

@MainActor
final class MockSleepTimerHandling: SleepTimerHandling {
  var onComplete: (() -> Void)?
  var onFadeStart: ((_ duration: TimeInterval) -> Void)?
  var onFadeCancel: (() -> Void)?
  var trackChangedCallCount = 0
  var trackPlayedToEndCallCount = 0
  var clearCallCount = 0

  @discardableResult func clear() -> Bool {
    clearCallCount += 1
    return true
  }

  func onTrackChanged() { trackChangedCallCount += 1 }
  func onTrackPlayedToEnd() { trackPlayedToEndCallCount += 1 }
}

// MARK: - Mock RetryHandling

@MainActor
final class MockRetryHandling: RetryHandling {
  var isRetryableResult = false
  var attemptRetryResult = false
  var resetCallCount = 0

  func isRetryable(_: Error?) -> Bool { isRetryableResult }
  func attemptRetry(startFromCurrentTime _: Bool) async -> Bool { attemptRetryResult }
  func reset() { resetCallCount += 1 }
}
