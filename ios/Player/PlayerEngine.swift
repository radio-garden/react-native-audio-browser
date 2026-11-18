import Foundation

#if canImport(NitroModules)
  import NitroModules
#endif

/// What the engine observes, reported to the Player to decide on.
enum EngineEvent {
  /// The load's item is now the player's current item.
  case itemPrepared(likelyToKeepUp: Bool)
  /// The load failed before an item existed.
  case loadFailed(Error)
  /// The asset loaded but cannot be played.
  case unplayable
  /// The resolved src is not a URL.
  case invalidSource(String)
  /// The player or its current item failed; `httpStatusCode` comes from the item's error log.
  case failed(Error?, httpStatusCode: Int?)
  case likelyToKeepUp(Bool)
  /// `waitingForNoItem`: the player is waiting because it has no current item.
  case timeControlStatus(PlayerTimeControlStatus, waitingForNoItem: Bool)
  /// Playback passed the item's first millisecond.
  case audioDidStart
  case playedToEnd
  /// The buffer emptied mid-playback.
  case stalled
  case timedMetadata(TimedMetadata)
}

/// The Player's one seam to the media stack: it carries out what the Player decides and reports
/// what it observes as `EngineEvent`s, deciding nothing itself (ADR 0012).
@MainActor protocol PlayerEngine: AnyObject {
  var onEvent: (@MainActor (EngineEvent) -> Void)? { get set }

  // MARK: The current item

  /// The player has a current item, which may be the now-playing carrier.
  var hasItem: Bool { get }
  /// The current load has an asset, whether or not its item is attached yet.
  var hasLoadedAsset: Bool { get }
  var currentTime: Double { get }
  /// For a live stream, the length of its seekable window; 0 when there is none.
  var duration: Double { get }
  var bufferedPosition: Double { get }
  /// Where a reload resumes; nil for a live item or no item.
  var resumePosition: Double? { get }
  /// The end of the seekable window, or nil when it has none.
  var liveEdge: Double? { get }
  /// The seekable window of a live item, or nil when it is not live or has none.
  var liveWindowSeconds: Double? { get }
  var isPlaybackLikelyToKeepUp: Bool { get }
  var isAirPlayRoute: Bool { get }
  /// 0–1.
  var volume: Float { get set }

  // MARK: Loading

  /// Resolves `src` afresh, so a short-lived URL is minted anew, and loads it, ending in
  /// `.itemPrepared`, `.loadFailed`, `.unplayable` or `.invalidSource`. With `shortStartBuffer`
  /// the item is ready once about 1 s is buffered, then buffers as configured.
  func resolveAndLoad(src: String, track: Track, shortStartBuffer: Bool)
  /// Cancels a resolve or load in flight; it reports nothing more.
  func cancelLoading()
  /// Drops the asset and stops observing the item, leaving the item in place.
  func dropAsset()
  /// Drops the asset and leaves the player with no item.
  func clearItem()
  /// Drops the asset and attaches an empty item, so the now-playing surfaces keep publishing
  /// without a connection (ADR 0005).
  func replaceItemWithNowPlayingCarrier()
  /// The current item reports nothing more, and stays attached.
  func stopObservingItem()
  /// The player itself failed: it cannot play again until `recreate()`.
  var playerFailed: Bool { get }
  /// Replaces the player with a new one, keeping its volume and rate.
  func recreate()

  // MARK: Transport

  func play()
  func pause()
  /// Caps how far ahead the current item buffers; nil restores the configured buffer.
  func capForwardBuffer(_ seconds: Double?)
  /// Applies at once only when `playing`, since a nonzero AVPlayer rate starts playback;
  /// otherwise from the next `play()`.
  func setRate(_ rate: Float, playing: Bool)
  /// Seeks exactly, with no tolerance; `completion` gets false when the seek was interrupted.
  func seek(to seconds: Double, completion: @escaping @MainActor (Bool) -> Void)
  func applyTimePitchAlgorithm()

  // MARK: Now playing and remote commands

  /// Plays a burst of silence, so iOS shows the now-playing surfaces after a failed load (ADR 0005).
  func primeNowPlaying()
  func loadNowPlayingMetadata(for track: Track)
  func clearNowPlaying()
  func renderNowPlaying()
  /// Drops what belonged to the previous track; the lines change at the next render.
  func nowPlayingTrackChanged()
  func applyTimedMetadata(_ metadata: TimedMetadata)
  func clearTimedMetadata()
  func updateRemoteRepeatMode(_ mode: RepeatMode)
  func updateRemoteShuffleMode(_ enabled: Bool)
  func updateSkipAvailability(canNext: Bool, canPrevious: Bool)

  // MARK: Audio session

  /// Activates the audio session asynchronously, off the main thread.
  func activateAudioSession()
  func cancelAudioSessionRelease()
  /// Releases the audio session after a delay, unless cancelled or the play intent comes back.
  func scheduleAudioSessionRelease()
}
