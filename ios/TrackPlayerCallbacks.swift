import AVFoundation
import MediaPlayer
import NitroModules

/// Callbacks for all player events.
///
/// This protocol defines the interface for receiving events from TrackPlayer.
/// HybridAudioBrowser implements this protocol to bridge events to JavaScript.
/// Note: Method names are prefixed to avoid conflicts with JS callback property names.
/// All callbacks are called from the main actor since TrackPlayer is @MainActor.
///
/// Extends PlaybackCoordinatorCallbacks for the state machine / coordinator callbacks
/// and RemoteCommandCallbacks for the remote-command events, then adds metadata,
/// seek, and configuration callbacks that are TrackPlayer-specific.
@MainActor
protocol TrackPlayerCallbacks: PlaybackCoordinatorCallbacks, RemoteCommandCallbacks {
  // MARK: - Metadata Events

  /// Called when common metadata is received.
  func playerDidReceiveCommonMetadata(_ metadata: [AVMetadataItem])

  /// Called when timed metadata is received.
  func playerDidReceiveTimedMetadata(_ metadata: [AVTimedMetadataGroup])

  /// Called when chapter metadata is received.
  func playerDidReceiveChapterMetadata(_ metadata: [AVTimedMetadataGroup])

  // MARK: - Playback Events

  /// Called when a seek operation completes.
  func playerDidCompleteSeek(position: Double, didFinish: Bool)

  /// Called when the duration is updated.
  func playerDidUpdateDuration(_ duration: Double)

  // MARK: - Remote Control Events

  // The command-center events live in RemoteCommandCallbacks, inherited above.

  /// Called when change playback position is triggered remotely.
  func remoteChangePlaybackPosition(position: Double)

  /// Called when play from ID is triggered remotely.
  func remotePlayId(id: String, index: Int?)

  /// Called when play from search is triggered remotely.
  func remotePlaySearch(query: String)

  // MARK: - Configuration Events

  /// Called when options are changed.
  func playerDidChangeOptions(_ options: PlayerUpdateOptions)
}
