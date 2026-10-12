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
/// and RemoteCommandCallbacks for the remote-command events, then adds the metadata
/// and seek callbacks that are TrackPlayer-specific.
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
}
