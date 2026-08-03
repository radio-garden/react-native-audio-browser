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

  // Player volume (0-1); ramped by the sleep-timer fade
  var volume: Float { get set }

  // AVPlayer state queries
  var currentTime: Double { get }
  var duration: Double { get }
  var bufferedPosition: Double { get }
  var hasLoadedAsset: Bool { get }

  // Item management
  func clearCurrentItem()
  func stopObservingCurrentItem()

  // Track loading. The full `track` is threaded alongside `src` so the
  // media-URL resolver can invoke the consumer's `media.resolve(track)`.
  func loadTrack(src: String, track: Track)
  /// Reloads the current track, re-running the media-URL resolver so a
  /// short-lived/expired URL is refreshed rather than replayed.
  func reloadTrack(startFromCurrentTime: Bool)
  func unloadTrack()
  func cancelMediaLoading()

  // Seek operations
  func seekToStart()
  func replayCurrentTrack()

  // Now Playing (behind protocol to avoid MediaPlayer import).
  // Elapsed/rate/duration are published automatically by MPNowPlayingSession;
  // only metadata and the explicit play/pause state flow through here.
  func loadNowPlayingMetadata(for track: Track)
  func clearNowPlaying()
  /// Reflects play/pause intent in the now-playing center. Auto-publishing fills
  /// the info dict but not the explicit playback state CarPlay reads for its button.
  func updateNowPlayingState(playWhenReady: Bool)

  // Remote commands (behind protocol to avoid MediaPlayer import)
  func updateRemoteRepeatMode(_ mode: RepeatMode)
  func updateRemoteShuffleMode(_ enabled: Bool)
  /// Greys out the remote/CarPlay next/previous buttons when the queue has no
  /// next/previous track to skip to.
  func updateSkipAvailability(canNext: Bool, canPrevious: Bool)
}
