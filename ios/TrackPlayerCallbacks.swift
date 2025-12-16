import AVFoundation
import MediaPlayer
import NitroModules

/// Callbacks for all player events.
///
/// This protocol defines the interface for receiving events from TrackPlayer.
/// HybridAudioBrowser implements this protocol to bridge events to JavaScript.
/// Note: Method names are prefixed to avoid conflicts with JS callback property names.
/// All callbacks are called from the main actor since TrackPlayer is @MainActor.
@MainActor
protocol TrackPlayerCallbacks: AnyObject {
  // MARK: - Playback State Events

  /// Called when the playback state changes (state + error).
  func playerDidChangePlayback(_ playback: Playback)

  /// Called when the active track changes.
  func playerDidChangeActiveTrack(_ event: PlaybackActiveTrackChangedEvent)

  /// Called periodically with playback progress updates.
  func playerDidUpdateProgress(_ event: PlaybackProgressUpdatedEvent)

  /// Called when playWhenReady changes.
  func playerDidChangePlayWhenReady(_ playWhenReady: Bool)

  /// Called when the playing state changes (playing or buffering flags).
  func playerDidChangePlayingState(_ state: PlayingState)

  /// Called when the playback queue ends (player reaches the end of the last track).
  func playerDidEndQueue(_ event: PlaybackQueueEndedEvent)

  /// Called when the queue changes (tracks added, removed, or replaced).
  func playerDidChangeQueue(_ tracks: [Track])

  /// Called when the repeat mode changes.
  func playerDidChangeRepeatMode(_ event: RepeatModeChangedEvent)

  /// Called when shuffle mode changes.
  func playerDidChangeShuffleEnabled(_ enabled: Bool)

  /// Called when the player encounters an error.
  func playerDidError(_ event: PlaybackErrorEvent)

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

  /// Called when play is triggered remotely.
  func remotePlay()

  /// Called when pause is triggered remotely.
  func remotePause()

  /// Called when stop is triggered remotely.
  func remoteStop()

  /// Called when toggle play/pause is triggered remotely.
  func remotePlayPause()

  /// Called when next is triggered remotely.
  func remoteNext()

  /// Called when previous is triggered remotely.
  func remotePrevious()

  /// Called when jump forward is triggered remotely.
  func remoteJumpForward(interval: Double)

  /// Called when jump backward is triggered remotely.
  func remoteJumpBackward(interval: Double)

  /// Called when seek is triggered remotely.
  func remoteSeek(position: Double)

  /// Called when change playback position is triggered remotely.
  func remoteChangePlaybackPosition(position: Double)

  /// Called when set rating is triggered remotely.
  func remoteSetRating(rating: Any)

  /// Called when play from ID is triggered remotely.
  func remotePlayId(id: String, index: Int?)

  /// Called when play from search is triggered remotely.
  func remotePlaySearch(query: String)

  /// Called when like is triggered remotely.
  func remoteLike()

  /// Called when dislike is triggered remotely.
  func remoteDislike()

  /// Called when bookmark is triggered remotely.
  func remoteBookmark()

  /// Called when repeat mode change is triggered remotely (CarPlay/lock screen).
  func remoteChangeRepeatMode(mode: RepeatMode)

  /// Called when shuffle mode change is triggered remotely (CarPlay/lock screen).
  func remoteChangeShuffleMode(enabled: Bool)

  /// Called when playback rate change is triggered remotely (CarPlay/lock screen).
  func remoteChangePlaybackRate(rate: Float)

  // MARK: - Configuration Events

  /// Called when options are changed.
  func playerDidChangeOptions(_ options: PlayerUpdateOptions)
}
