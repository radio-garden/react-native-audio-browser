import Foundation

#if canImport(NitroModules)
  import NitroModules
#endif

/// What the media stack reports to the Player.
enum EngineEvent {
  /// The loaded item is now current and observed.
  case itemPrepared(likelyToKeepUp: Bool)
  /// Loading the asset failed; the retry loop classifies the error.
  case loadFailed(Error)
  /// The asset loaded but cannot play.
  case unplayable
  /// The source is not a URL.
  case invalidSource(String)
  /// The player or its item failed, with the HTTP status the server last answered, if any.
  case failed(Error?, httpStatusCode: Int?)
  case likelyToKeepUp(Bool)
  /// `waitingForNoItem`: the player waits because it has no item to play.
  case timeControlStatus(PlayerTimeControlStatus, waitingForNoItem: Bool)
  /// Playback crossed the first millisecond.
  case audioDidStart
  case playedToEnd
  /// The buffer emptied mid-playback.
  case stalled
}

/// The media stack under the Player: the AVPlayer and its items, media loading, now playing and
/// the remote commands. It carries out what the Player decides and reports what it observes
/// through `onEvent`; it holds no playback policy. AVPlayerEngine is the one implementation;
/// tests use a mock.
@MainActor protocol PlayerEngine: AnyObject {
  var onEvent: (@MainActor (EngineEvent) -> Void)? { get set }

  // MARK: The current item

  var hasItem: Bool { get }
  var hasLoadedAsset: Bool { get }
  var currentTime: Double { get }
  var duration: Double { get }
  var bufferedPosition: Double { get }
  /// The position a reload resumes from: nil without an item, or for an indefinite duration.
  var resumePosition: Double? { get }
  /// The end of a live stream's seekable window, when it has one.
  var liveEdge: Double? { get }
  var isPlaybackLikelyToKeepUp: Bool { get }
  var isAirPlayRoute: Bool { get }
  /// 0–1; ramped by the sleep-timer fade.
  var volume: Float { get set }

  // MARK: Loading

  /// Runs the media-URL resolver for `track`, so a short-lived URL is minted afresh, and loads
  /// the result.
  func resolveAndLoad(src: String, track: Track)
  func cancelLoading()
  /// Drops the asset and stops observing the item, leaving it in place for the reload.
  func dropAsset()
  /// Drops the asset and removes the item.
  func clearItem()
  func stopObservingItem()
  /// Replaces the AVPlayer: every handle to the old one is invalid.
  func recreate()

  // MARK: Transport

  func play()
  func pause()
  /// Stages `rate` for the next play; applies it now only when `playing`, since a nonzero
  /// AVPlayer rate is a play.
  func setRate(_ rate: Float, playing: Bool)
  /// Seeks the current item exactly; `completion` runs on the main actor.
  func seek(to seconds: Double, completion: @escaping @MainActor (Bool) -> Void)
  func applyTimePitchAlgorithm()

  // MARK: Now playing and remote commands

  /// Renders silence so iOS elects the app onto the now-playing surfaces (ADR 0005).
  func primeNowPlaying()
  func loadNowPlayingMetadata(for track: Track)
  func clearNowPlaying()
  func updateRemoteRepeatMode(_ mode: RepeatMode)
  func updateRemoteShuffleMode(_ enabled: Bool)
  /// Greys out next/previous on the remote surfaces when the queue has nowhere to skip.
  func updateSkipAvailability(canNext: Bool, canPrevious: Bool)
}
