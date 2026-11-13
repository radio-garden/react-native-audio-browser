import Foundation

#if canImport(NitroModules)
  import NitroModules
#endif

/// What the engine reports to the Player.
enum EngineEvent {
  /// The loaded item is now the player's current item, and observed.
  case itemPrepared(likelyToKeepUp: Bool)
  /// Loading the resolved asset failed; the retry loop classifies the error.
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
  /// Playback crossed its first millisecond.
  case audioDidStart
  case playedToEnd
  /// The buffer emptied mid-playback.
  case stalled
  /// A timed-metadata group (a live stream's song line), decoded with the track's ICY charset.
  case timedMetadata(TimedMetadata)
}

/// The media stack under the Player: the AVPlayer and its items, loading, now playing, remote
/// commands and the audio session. It carries out what the Player decides and reports what it
/// observes through `onEvent`; it holds no playback policy (ADR 0012). Tests use a mock.
@MainActor protocol PlayerEngine: AnyObject {
  var onEvent: (@MainActor (EngineEvent) -> Void)? { get set }

  // MARK: The current item

  /// True also for the item a failed load leaves in place to carry now playing.
  var hasItem: Bool { get }
  var hasLoadedAsset: Bool { get }
  var currentTime: Double { get }
  var duration: Double { get }
  var bufferedPosition: Double { get }
  /// The position a reload resumes from: nil without an item, for an indefinite duration, or when the item's time isn't finite.
  var resumePosition: Double? { get }
  /// The end of the seekable window; nil when there is none.
  var liveEdge: Double? { get }
  /// How far back a live item can seek from its live edge; nil for an on-demand item or none.
  var liveWindowSeconds: Double? { get }
  var isPlaybackLikelyToKeepUp: Bool { get }
  var isAirPlayRoute: Bool { get }
  /// 0–1.
  var volume: Float { get set }

  // MARK: Loading

  /// Resolves `src` for `track` afresh, so a short-lived URL is minted anew, and loads it. The
  /// outcome arrives as `itemPrepared`, `loadFailed`, `unplayable` or `invalidSource`.
  func resolveAndLoad(src: String, track: Track)
  func cancelLoading()
  /// Drops the asset and stops observing the item, leaving the item in place for the reload.
  func dropAsset()
  /// Drops the asset and removes the item.
  func clearItem()
  /// Drops the asset and its connection, leaving in the item's place one with no media that carries
  /// the now-playing info: auto-publishing reads it only from the current item (docs/adr/0005).
  func replaceItemWithNowPlayingCarrier()
  func stopObservingItem()
  /// Whether the AVPlayer itself has failed, not only its item: it plays nothing until recreated.
  var playerFailed: Bool { get }
  /// Replaces the AVPlayer: every handle to the old one is invalid.
  func recreate()

  // MARK: Transport

  func play()
  func pause()
  /// Caps how far ahead the current item loads, in seconds; nil restores the configured buffer.
  func capForwardBuffer(_ seconds: Double?)
  /// Stages `rate` for the next play; applies it now only when `playing`, since a nonzero
  /// AVPlayer rate starts playback.
  func setRate(_ rate: Float, playing: Bool)
  /// Seeks exactly, with no tolerance; `completion` gets false when the seek was interrupted.
  func seek(to seconds: Double, completion: @escaping @MainActor (Bool) -> Void)
  func applyTimePitchAlgorithm()

  // MARK: Now playing and remote commands

  /// Renders silence so iOS elects the app onto the now-playing surfaces when a load renders
  /// no audio (ADR 0005).
  func primeNowPlaying()
  func loadNowPlayingMetadata(for track: Track)
  func clearNowPlaying()
  /// Re-renders the now-playing lines from the Player's state.
  func renderNowPlaying()
  /// Drops the previous track's override, flash and timed metadata; the lines change at the next
  /// render.
  func nowPlayingTrackChanged()
  func applyTimedMetadata(_ metadata: TimedMetadata)
  /// Drops the stream's title, so the next render shows the track's own lines.
  func clearTimedMetadata()
  func updateRemoteRepeatMode(_ mode: RepeatMode)
  func updateRemoteShuffleMode(_ enabled: Bool)
  func updateSkipAvailability(canNext: Bool, canPrevious: Bool)

  // MARK: Audio session

  /// Activates the audio session off the main thread, then re-requests the now-playing session,
  /// which the system may have declined while the audio session was inactive.
  func activateAudioSession()
  func cancelAudioSessionRelease()
  /// Releases the audio session after a debounce, unless playback resumes or
  /// `cancelAudioSessionRelease()` runs first.
  func scheduleAudioSessionRelease()
}
