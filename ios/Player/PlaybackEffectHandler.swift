#if canImport(NitroModules)
import NitroModules
#endif

/// Protocol through which PlaybackCoordinator triggers AVPlayer-specific operations.
/// In production, TrackPlayer implements it. In tests, a mock does.
@MainActor protocol PlaybackEffectHandler: AnyObject {
  // Playback control
  func startPlayback()
  func pausePlayback()
  func setTimePitchingAlgorithmForCurrentItem()

  // AVPlayer state queries
  var currentTime: Double { get }
  var duration: Double { get }
  var bufferedPosition: Double { get }
  var hasLoadedAsset: Bool { get }

  // Item management
  func clearCurrentItem()
  func stopObservingCurrentItem()

  // Track loading
  func loadTrack(src: String)
  func reloadTrack(startFromCurrentTime: Bool)
  func unloadTrack()
  func cancelMediaLoading()

  // Seek operations
  func seekToStart()
  func replayCurrentTrack()

  // Now Playing (behind protocol to avoid MediaPlayer import)
  func updateNowPlayingValues(duration: Double, rate: Float, currentTime: Double)
  func updateNowPlayingState(playWhenReady: Bool)
  func loadNowPlayingMetadata(for track: Track, rate: Float)
  func resetNowPlayingValues()
  func clearNowPlaying()
  func setNowPlayingCurrentTime(seconds: Double)

  // Remote commands (behind protocol to avoid MediaPlayer import)
  func updateRemoteRepeatMode(_ mode: RepeatMode)
  func updateRemoteShuffleMode(_ enabled: Bool)
  /// Greys out the remote/CarPlay next/previous buttons when the queue has no
  /// next/previous track to skip to.
  func updateSkipAvailability(canNext: Bool, canPrevious: Bool)
}
