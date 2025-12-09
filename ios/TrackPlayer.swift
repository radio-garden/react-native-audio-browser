import Foundation
import MediaPlayer
import NitroModules
import os.log

/// Result of media URL resolution
struct MediaResolvedUrl {
  let url: String
  let headers: [String: String]?
  let userAgent: String?
}

class TrackPlayer {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "TrackPlayer")

  public let nowPlayingInfoController: NowPlayingInfoController
  public let remoteCommandController: RemoteCommandController
  public let sleepTimerManager = SleepTimerManager()
  private weak var callbacks: TrackPlayerCallbacks?
  private var lastIndex: Int = -1
  private var lastTrack: Track?

  /// Callback to resolve media URLs with optional transform
  var mediaUrlResolver: ((String) async -> MediaResolvedUrl)?

  /// The repeat mode for the queue player.
  public var repeatMode: RepeatMode = .off {
    didSet {
      guard oldValue != repeatMode else { return }
      assertMainThread()

      callbacks?.playerDidChangeRepeatMode(
        RepeatModeChangedEvent(repeatMode: repeatMode)
      )
    }
  }

  // MARK: - Queue Properties

  private func assertMainThread() {
    assert(Thread.isMainThread, "TrackPlayer queue must be accessed from the main thread")
  }

  /**
   The index of the current track. `-1` when there is no current track
   */
  public private(set) var currentIndex: Int = -1

  /**
   All tracks held by the queue.
   */
  public private(set) var tracks: [Track] = []

  public var currentTrack: Track? {
    assertMainThread()
    guard currentIndex >= 0, currentIndex < tracks.count else { return nil }
    return tracks[currentIndex]
  }

  /**
   The upcoming tracks in the queue.
   */
  public var nextTracks: [Track] {
    assertMainThread()
    guard currentIndex >= 0, currentIndex < tracks.count - 1 else { return [] }
    return Array(tracks[currentIndex + 1 ..< tracks.count])
  }

  /**
   The previous tracks held by the queue.
   */
  public var previousTracks: [Track] {
    assertMainThread()
    guard currentIndex > 0 else { return [] }
    return Array(tracks[0 ..< currentIndex])
  }

  /**
   Whether the current track is the last track in the queue.
   */
  private var isLastTrack: Bool {
    currentIndex == tracks.count - 1
  }

  // MARK: - AVPlayer Properties (from AVPlayerWrapper)

  /// Represents a seek operation that's pending while a track loads
  private struct PendingSeek {
    let time: TimeInterval
    let completion: ((Bool) -> Void)?

    func execute(on player: AVPlayer, delegate: TrackPlayer?) {
      let cmTime = CMTimeMakeWithSeconds(time, preferredTimescale: 1000)
      player
        .seek(to: cmTime, toleranceBefore: CMTime.zero, toleranceAfter: CMTime.zero) { finished in
          delegate?.handleSeekCompleted(to: Double(time), didFinish: finished)
          completion?(finished)
        }
    }

    func cancel() {
      completion?(false)
    }
  }

  private var avPlayer = AVPlayer()

  private lazy var playerObserver: PlayerStateObserver = .init(
    onStatusChange: { [weak self] status in
      self?.avPlayerStatusDidChange(status)
    },
    onTimeControlStatusChange: { [weak self] status in
      self?.avPlayerDidChangeTimeControlStatus(status)
    }
  )

  private lazy var playerTimeObserver: PlayerTimeObserver = .init(
    periodicObserverTimeInterval: CMTime(seconds: 1, preferredTimescale: 1000),
    onAudioDidStart: { [weak self] in
      self?.audioDidStart()
    },
    onSecondElapsed: { [weak self] seconds in
      guard let self else { return }
      if automaticallyUpdateNowPlayingInfo {
        setNowPlayingCurrentTime(seconds: seconds)
      }
    }
  )

  private lazy var playerItemNotificationObserver: PlayerItemNotificationObserver = .init(
    onDidPlayToEndTime: { [weak self] in
      self?.handleTrackDidPlayToEndTime()
    },
    onFailedToPlayToEndTime: { [weak self] in
      self?.playbackError = TrackPlayerError.PlaybackError.playbackFailed
    }
  )

  private lazy var playerItemObserver: PlayerItemPropertyObserver = .init(
    onDurationUpdate: { [weak self] duration in
      self?.callbacks?.playerDidUpdateDuration(duration)
    },
    onPlaybackLikelyToKeepUpUpdate: { [weak self] isLikely in
      self?.avItemDidUpdatePlaybackLikelyToKeepUp(isLikely)
    },
    onTimedMetadataReceived: { [weak self] groups in
      self?.callbacks?.playerDidReceiveTimedMetadata(groups)
    }
  )

  private lazy var progressUpdateManager: PlaybackProgressUpdateManager =
    PlaybackProgressUpdateManager { [weak self] in
      guard let self, currentIndex >= 0 else { return }
      let progressEvent = PlaybackProgressUpdatedEvent(
        track: Double(currentIndex),
        position: currentTime,
        duration: duration,
        buffered: bufferedPosition
      )
      callbacks?.playerDidUpdateProgress(progressEvent)
    }

  lazy var playingStateManager: PlayingStateManager = PlayingStateManager { [weak self] state in
    self?.callbacks?.playerDidChangePlayingState(state)
  }

  private var pendingSeek: PendingSeek?
  private var asset: AVAsset?
  private var url: URL?
  private var urlOptions: [String: Any]?
  private(set) var playbackError: TrackPlayerError.PlaybackError? {
    didSet {
      guard oldValue != playbackError else { return }
      assertMainThread()

      // Setting an error should set state to .error
      if let _ = playbackError, state != .error {
        state = .error
      }

      callbacks?.playerDidError(PlaybackErrorEvent(error: playbackError?.toNitroError()))
    }
  }

  private(set) var lastPlayerTimeControlStatus: AVPlayer.TimeControlStatus = .paused

  public func getPlayback() -> Playback {
    return Playback(state: state, error: playbackError?.toNitroError())
  }

  public func getRepeatMode() -> RepeatMode {
    return repeatMode
  }

  public func setRepeatMode(_ mode: RepeatMode) {
    repeatMode = mode
  }

  /**
   Set this to false to disable automatic updating of now playing info for control center and lock screen.
   */
  public var automaticallyUpdateNowPlayingInfo: Bool = true

  /**
   Controls the time pitch algorithm applied to each track loaded into the player.
   If the loaded `AudioItem` conforms to `TimePitcher`-protocol this will be overriden.
   */
  public var audioTimePitchAlgorithm: AVAudioTimePitchAlgorithm = .timeDomain

  /**
   Default remote commands to use for each playing track
   */
  public var remoteCommands: [RemoteCommand] = [] {
    didSet {
      if let track = currentTrack {
        enableRemoteCommands(remoteCommands)
      }
    }
  }

  /**
    Handles the `playWhenReady` setting while executing a given action.

    This method takes an optional `Bool` value and a closure representing an action to execute.
    If the `Bool` value is not `nil`, `self.playWhenReady` is set accordingly either before or
    after executing the action.

    - Parameters:
      - playWhenReady: Optional `Bool` to set `self.playWhenReady`.
                       - If `true`, `self.playWhenReady` will be set after executing the action.
                       - If `false`, `self.playWhenReady` will be set before executing the action.
                       - If `nil`, `self.playWhenReady` will not be changed.
      - action: A closure representing the action to execute. This closure can throw an error.

    - Throws: This function will propagate any errors thrown by the `action` closure.
   */
  func handlePlayWhenReady(_ playWhenReady: Bool?, action: () throws -> Void) rethrows {
    if playWhenReady == false {
      self.playWhenReady = false
    }

    try action()

    if playWhenReady == true {
      self.playWhenReady = true
    }
  }

  // MARK: - AVPlayer State and Computed Properties

  private(set) var state: PlaybackState = .none {
    didSet {
      guard oldValue != state else { return }
      assertMainThread()

      // Clear error when transitioning away from error state
      if oldValue == .error, state != .error {
        playbackError = nil
      }

      switch state {
      case .ready:
        setTimePitchingAlgorithmForCurrentItem()
        // When item becomes ready and playWhenReady is true, start playback
        if playWhenReady {
          applyAVPlayerRate()
        }
      case .loading:
        setTimePitchingAlgorithmForCurrentItem()
      default: break
      }

      switch state {
      case .ready, .loading, .playing, .paused:
        if automaticallyUpdateNowPlayingInfo {
          updateNowPlayingPlaybackValues()
        }
      default: break
      }

      let playback = Playback(state: state, error: playbackError?.toNitroError())
      callbacks?.playerDidChangePlayback(playback)

      // Emit queue ended event when playback ends on the last track
      // This matches Android's behavior (TrackPlayer.kt:642-646)
      if state == .ended, isLastTrack {
        let event = PlaybackQueueEndedEvent(
          track: Double(currentIndex),
          position: currentTime
        )
        callbacks?.playerDidEndQueue(event)
      }

      progressUpdateManager.onPlaybackStateChanged(state)
      playingStateManager.update(playWhenReady: playWhenReady, state: state)
    }
  }

  var playbackActive: Bool {
    switch state {
    case .none, .stopped, .ended, .error:
      return false
    default: return true
    }
  }

  var reasonForWaitingToPlay: AVPlayer.WaitingReason? {
    avPlayer.reasonForWaitingToPlay
  }

  // MARK: - Getters from AVPlayerWrapper

  /**
   The elapsed playback time of the current track.
   */
  public var currentTime: Double {
    let seconds = avPlayer.currentTime().seconds
    return seconds.isNaN ? 0 : seconds
  }

  /**
   The duration of the current track.
   */
  public var duration: Double {
    guard let item = avPlayer.currentItem else { return 0.0 }

    if !item.asset.duration.seconds.isNaN {
      return item.asset.duration.seconds
    }
    if !item.duration.seconds.isNaN {
      return item.duration.seconds
    }
    if let seekable = item.seekableTimeRanges.last?.timeRangeValue.duration.seconds,
       !seekable.isNaN
    {
      return seekable
    }
    return 0.0
  }

  /**
   The bufferedPosition of the active track
   */
  public var bufferedPosition: Double {
    avPlayer.currentItem?.loadedTimeRanges.last?.timeRangeValue.end.seconds ?? 0
  }

  /**
   The current state of the underlying `TrackPlayer`.
   */
  public var playerState: PlaybackState {
    state
  }

  // MARK: - Setters for AVPlayerWrapper

  /**
   Whether the player should start playing automatically when the track is ready.
   */
  public var playWhenReady: Bool = false {
    didSet {
      if playWhenReady == true, state == .error || state == .stopped {
        reload(startFromCurrentTime: state == .error)
      }
      applyAVPlayerRate()

      if oldValue != playWhenReady {
        callbacks?.playerDidChangePlayWhenReady(playWhenReady)
        playingStateManager.update(playWhenReady: playWhenReady, state: state)
      }
    }
  }

  /**
   The amount of seconds to be buffered by the player. Default value is 0 seconds, this means the AVPlayer will choose an appropriate level of buffering. Setting `bufferDuration` to larger than zero automatically disables `automaticallyWaitsToMinimizeStalling`. Setting it back to zero automatically enables `automaticallyWaitsToMinimizeStalling`.

   [Read more from Apple Documentation](https://developer.apple.com/documentation/avfoundation/avplayeritem/1643630-preferredforwardbufferduration)
   */
  public var bufferDuration: TimeInterval = 0 {
    didSet {
      avPlayer.automaticallyWaitsToMinimizeStalling = bufferDuration == 0
    }
  }

  /**
   Indicates whether the player should automatically delay playback in order to minimize stalling. Setting this to true will also set `bufferDuration` back to `0`.

   [Read more from Apple Documentation](https://developer.apple.com/documentation/avfoundation/avplayer/1643482-automaticallywaitstominimizestal)
   */
  public var automaticallyWaitsToMinimizeStalling: Bool {
    get { avPlayer.automaticallyWaitsToMinimizeStalling }
    set {
      if newValue {
        bufferDuration = 0
      }
      avPlayer.automaticallyWaitsToMinimizeStalling = newValue
    }
  }

  public var volume: Float {
    get { avPlayer.volume }
    set { avPlayer.volume = newValue }
  }

  public var isMuted: Bool {
    get { avPlayer.isMuted }
    set { avPlayer.isMuted = newValue }
  }

  public var rate: Float = 1.0 {
    didSet {
      applyAVPlayerRate()
      if automaticallyUpdateNowPlayingInfo {
        updateNowPlayingPlaybackValues()
      }
    }
  }

  // MARK: - Init

  public init(
    nowPlayingInfoController: NowPlayingInfoController = NowPlayingInfoController(),
    callbacks: TrackPlayerCallbacks? = nil
  ) {
    self.nowPlayingInfoController = nowPlayingInfoController
    remoteCommandController = RemoteCommandController(callbacks: callbacks)
    self.callbacks = callbacks

    // Configure sleep timer
    sleepTimerManager.onComplete = { [weak self] in
      self?.pause()
    }

    setupAVPlayer()
  }

  // MARK: - Player Actions

  /**
   Will replace the current track with a new one and load it into the player.

   - parameter track: The Track to replace the current track.
   - parameter playWhenReady: Optional, whether to start playback when the track is ready.
   */
  public func load(_ track: Track, playWhenReady: Bool? = nil) {
    handlePlayWhenReady(playWhenReady) {
      replaceCurrentTrackWith(track)
    }
  }

  /**
   Toggle playback status.
   */
  public func togglePlaying() {
    switch avPlayer.timeControlStatus {
    case .playing, .waitingToPlayAtSpecifiedRate:
      pause()
    case .paused:
      play()
    @unknown default:
      fatalError("Unknown AVPlayer.timeControlStatus")
    }
  }

  /**
   Start playback
   */
  public func play() {
    playWhenReady = true
  }

  /**
   Pause playback
   */
  public func pause() {
    playWhenReady = false
  }

  /**
   Toggle playback between play and pause
   */
  public func togglePlayback() {
    playWhenReady = !playWhenReady
  }

  /**
   Stop playback
   */
  public func stop() {
    let wasActive = playbackActive
    state = .stopped
    clearCurrentAVItem()
    playWhenReady = false
  }

  /**
   Reload the current track.
   */
  public func reload(startFromCurrentTime: Bool) {
    var time: Double? = nil
    if startFromCurrentTime {
      if let currentItem = avPlayer.currentItem {
        if !currentItem.duration.isIndefinite {
          time = currentItem.currentTime().seconds
        }
      }
    }
    loadAVPlayer()
    if let time {
      seekTo(time)
    }
  }

  /**
   Seek to a specific time in the track.
   */
  public func seekTo(_ seconds: TimeInterval) {
    seekTo(seconds, completion: { _ in })
  }

  /**
   Seek to a specific time in the track with a completion handler.

   - parameter seconds: The time to seek to.
   - parameter completion: Called when the seek operation completes. The Bool parameter indicates whether the seek finished successfully (true) or was interrupted/deferred (false).
   */
  public func seekTo(_ seconds: TimeInterval, completion: @escaping (Bool) -> Void) {
    // If an track is currently being loaded asynchronously, defer the seek until it's ready.
    if state == .loading {
      // Cancel any previous pending seek before creating a new one
      pendingSeek?.cancel()
      pendingSeek = PendingSeek(time: seconds, completion: completion)
    } else if avPlayer.currentItem != nil {
      let time = CMTimeMakeWithSeconds(seconds, preferredTimescale: 1000)
      avPlayer
        .seek(to: time, toleranceBefore: CMTime.zero, toleranceAfter: CMTime.zero) { finished in
          self.handleSeekCompleted(to: Double(seconds), didFinish: finished)
          completion(finished)
        }
    } else {
      // No track loaded and not loading - seek fails immediately
      completion(false)
    }
  }

  /**
   Seek by relative a time offset in the track.
   */
  public func seekBy(_ offset: TimeInterval) {
    // Calculate the target time based on current state
    let targetTime: TimeInterval
    if state == .loading {
      // If loading, offset from pending seek (or 0 if no pending seek)
      targetTime = (pendingSeek?.time ?? 0) + offset
    } else if let currentItem = avPlayer.currentItem {
      // If playing, offset from current position
      targetTime = currentItem.currentTime().seconds + offset
    } else {
      // No track and not loading - nothing to seek in
      return
    }

    // Delegate to absolute seek
    seekTo(targetTime)
  }

  // MARK: - Remote Command Center

  func enableRemoteCommands(_ commands: [RemoteCommand]) {
    remoteCommandController.enable(commands: commands)
  }

  // MARK: - NowPlayingInfo

  /**
   Loads NowPlayingInfo-meta values with the values found in the current track. Use this if a change to the track is made and you want to update the `NowPlayingInfoController`s values.

   Reloads:
   - Artist
   - Title
   - Album title
   - Album artwork
   */
  public func loadNowPlayingMetaValues() {
    guard let track = currentTrack else { return }

    nowPlayingInfoController.set(keyValues: [
      MediaItemProperty.artist(track.artist),
      MediaItemProperty.title(track.title),
      MediaItemProperty.albumTitle(track.album),
    ])
    loadArtworkForTrack(track)
  }

  /**
   Resyncs the playbackvalues of the currently playing track.

   Will resync:
   - Current time
   - Duration
   - Playback rate
   */
  func updateNowPlayingPlaybackValues() {
    nowPlayingInfoController.set(keyValues: [
      MediaItemProperty.duration(duration),
      NowPlayingInfoProperty.playbackRate(playWhenReady ? Double(rate) : 0),
      NowPlayingInfoProperty.elapsedPlaybackTime(currentTime),
    ])
  }

  public func clear() {
    clearTracks()
    let playbackWasActive = playbackActive
    unloadAVPlayer()
    nowPlayingInfoController.clear()
  }

  // MARK: - Private

  private func setNowPlayingCurrentTime(seconds: Double) {
    nowPlayingInfoController.set(
      keyValue: NowPlayingInfoProperty.elapsedPlaybackTime(seconds)
    )
  }

  private func loadArtworkForTrack(_ track: Track) {
    track.loadArtwork { image in
      if let image {
        let artwork = MPMediaItemArtwork(boundsSize: image.size, requestHandler: { _ in image })
        self.nowPlayingInfoController.set(keyValue: MediaItemProperty.artwork(artwork))
      } else {
        self.nowPlayingInfoController.set(keyValue: MediaItemProperty.artwork(nil))
      }
    }
  }

  private func setTimePitchingAlgorithmForCurrentItem() {
    // Use player's default pitch algorithm (per-track pitch control not in Nitro API)
    avPlayer.currentItem?.audioTimePitchAlgorithm = audioTimePitchAlgorithm
  }

  // MARK: - AVPlayer Management Methods (from AVPlayerWrapper)

  private func applyAVPlayerRate() {
    avPlayer.rate = playWhenReady ? rate : 0
  }

  private func clearCurrentAVItem() {
    guard let currentAsset = asset else {
      avPlayer.replaceCurrentItem(with: nil)
      return
    }

    stopObservingAVPlayerItem()

    // Don't call currentAsset.cancelLoading() - it blocks the main thread for 500ms+
    // Instead, cancel on a background queue
    DispatchQueue.global(qos: .utility).async {
      currentAsset.cancelLoading()
    }

    self.asset = nil
    pendingSeek?.cancel()
    pendingSeek = nil

    avPlayer.replaceCurrentItem(with: nil)
  }

  /// Prepares for loading a new item by stopping playback and clearing state,
  /// but WITHOUT calling the slow cancelLoading() or replaceCurrentItem(nil).
  /// The old asset's async callbacks are safely ignored because loadAVPlayer()
  /// checks `if pendingAsset != self.asset { return }` before using results.
  private func prepareForNewItem() {
    guard let asset else { return }

    // Stop playback immediately so old track doesn't keep playing while new one loads
    avPlayer.rate = 0

    stopObservingAVPlayerItem()

    // Cancel loading on background queue to avoid blocking main thread (500ms+).
    // The old asset's callbacks are ignored anyway via the pendingAsset check.
    let oldAsset = asset
    DispatchQueue.global(qos: .utility).async {
      oldAsset.cancelLoading()
    }
    self.asset = nil

    // Clear any pending seek to prevent it from being applied to the next track that loads.
    // Without this, a seek called before any track was loaded could incorrectly apply to
    // an unrelated track that loads later.
    pendingSeek?.cancel()
    pendingSeek = nil
  }

  private func startObservingAVPlayerItem(_ avItem: AVPlayerItem) {
    playerItemObserver.startObserving(item: avItem)
    playerItemNotificationObserver.startObserving(item: avItem)
  }

  private func stopObservingAVPlayerItem() {
    playerItemObserver.stopObservingCurrentItem()
    playerItemNotificationObserver.stopObservingCurrentItem()
  }

  private func recreateAVPlayer() {
    playbackError = nil
    playerTimeObserver.unregisterForBoundaryTimeEvents()
    playerTimeObserver.unregisterForPeriodicEvents()
    playerObserver.stopObserving()
    stopObservingAVPlayerItem()
    clearCurrentAVItem()

    avPlayer = AVPlayer()
    setupAVPlayer()
  }

  private func setupAVPlayer() {
    // disabled since we're not making use of video playback
    avPlayer.allowsExternalPlayback = false

    playerObserver.avPlayer = avPlayer
    playerObserver.startObserving()

    playerTimeObserver.avPlayer = avPlayer
    playerTimeObserver.registerForBoundaryTimeEvents()
    playerTimeObserver.registerForPeriodicTimeEvents()

    applyAVPlayerRate()
  }

  func loadAVPlayer() {
    assertMainThread()
    if state == .error {
      recreateAVPlayer()
    } else {
      // Use prepareForNewItem() instead of clearCurrentAVItem() - it skips the slow
      // cancelLoading() call and unnecessary replaceCurrentItem(with: nil).
      // The old item remains until replaceCurrentItem(with: avItem) below, and
      // stale async callbacks are ignored via `if pendingAsset != self.asset { return }`.
      prepareForNewItem()
    }
    if let url {
      let pendingAsset = AVURLAsset(url: url, options: urlOptions)
      asset = pendingAsset
      state = .loading

      // Load metadata keys asynchronously and separate from playable, to allow that to execute as
      // quickly as it can
      let metadataKeys = ["commonMetadata", "availableChapterLocales", "availableMetadataFormats"]
      pendingAsset.loadValuesAsynchronously(
        forKeys: metadataKeys,
        completionHandler: { [weak self] in
          guard let self else { return }
          if pendingAsset != asset { return }

          let commonData = pendingAsset.commonMetadata
          if !commonData.isEmpty {
            callbacks?.playerDidReceiveCommonMetadata(commonData)
          }

          if !pendingAsset.availableChapterLocales.isEmpty {
            for locale in pendingAsset.availableChapterLocales {
              let chapters = pendingAsset.chapterMetadataGroups(
                withTitleLocale: locale,
                containingItemsWithCommonKeys: nil
              )
              callbacks?.playerDidReceiveChapterMetadata(chapters)
            }
          } else {
            for format in pendingAsset.availableMetadataFormats {
              let timeRange = CMTimeRange(
                start: CMTime(seconds: 0, preferredTimescale: 1000),
                end: pendingAsset.duration
              )
              let group = AVTimedMetadataGroup(
                items: pendingAsset.metadata(forFormat: format),
                timeRange: timeRange
              )
              callbacks?.playerDidReceiveTimedMetadata([group])
            }
          }
        }
      )

      // Load playable portion of the track and commence when ready
      let playableKeys = ["playable"]
      pendingAsset.loadValuesAsynchronously(
        forKeys: playableKeys,
        completionHandler: { [weak self] in
          guard let self else { return }

          DispatchQueue.main.async {
            if pendingAsset != self.asset { return }

            for key in playableKeys {
              var error: NSError?
              let keyStatus = pendingAsset.statusOfValue(forKey: key, error: &error)
              switch keyStatus {
              case .failed:
                self.playbackError = TrackPlayerError.PlaybackError.failedToLoadKeyValue
                return
              case .cancelled, .loading, .unknown:
                return
              case .loaded:
                break
              default: break
              }
            }

            if !pendingAsset.isPlayable {
              self.playbackError = TrackPlayerError.PlaybackError.trackWasUnplayable
              return
            }

            let avItem = AVPlayerItem(
              asset: pendingAsset,
              automaticallyLoadedAssetKeys: playableKeys
            )
            avItem.preferredForwardBufferDuration = self.bufferDuration
            self.avPlayer.replaceCurrentItem(with: avItem)
            self.startObservingAVPlayerItem(avItem)
            self.applyAVPlayerRate()

            // Execute any pending seek operation
            if let pending = self.pendingSeek {
              self.pendingSeek = nil
              pending.execute(on: self.avPlayer, delegate: self)
            }
          }
        }
      )
    }
  }

  func unloadAVPlayer() {
    clearCurrentAVItem()
    state = .none
  }

  // MARK: - Internal Event Handlers

  /**
   Sets the progress update interval.
   - Parameter interval: The interval in seconds, or nil to disable progress updates
   */
  public func setProgressUpdateInterval(_ interval: TimeInterval?) {
    progressUpdateManager.setUpdateInterval(interval)
  }

  private func handleSeekCompleted(to seconds: Double, didFinish: Bool) {
    if automaticallyUpdateNowPlayingInfo {
      setNowPlayingCurrentTime(seconds: Double(seconds))
    }
    callbacks?.playerDidCompleteSeek(position: seconds, didFinish: didFinish)
  }

  func handleTrackDidPlayToEndTime() {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }

      // Check if sleep timer should trigger on track end
      sleepTimerManager.onTrackPlayedToEnd()

      if repeatMode == .track {
        replay()
      } else if repeatMode == .queue || !isLastTrack {
        next()
      } else {
        state = .ended
      }
    }
  }

  // MARK: - Observer Callbacks

  func avPlayerDidChangeTimeControlStatus(_ status: AVPlayer.TimeControlStatus) {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      switch status {
      case .paused:
        let currentState = state
        let currentTime = currentTime
        let duration = duration
        // Ignore pauses when near track end - let handleTrackDidPlayToEndTime handle track
        // completion
        let nearTrackEnd = currentTime >= duration - 0.5 && duration > 0

        // Completely ignore pause events when near track end to avoid race with
        // handleTrackDidPlayToEndTime
        if nearTrackEnd {
          // Ignore - track completion will be handled by handleTrackDidPlayToEndTime
        } else if asset == nil, currentState != .stopped {
          state = .none
        } else if currentState != .error, currentState != .stopped {
          // Only update state, never modify playWhenReady
          // playWhenReady represents user intent and should only change via explicit user actions
          if !playWhenReady {
            state = .paused
          }
          // If playWhenReady is true, this is likely buffering/seeking - don't change state
        }
      case .waitingToPlayAtSpecifiedRate:
        if asset != nil {
          state = .buffering
        }
      case .playing:
        state = .playing
      @unknown default:
        break
      }
    }
  }

  func avPlayerStatusDidChange(_ status: AVPlayer.Status) {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      if status == .failed {
        let error = avPlayer.currentItem?.error as NSError?
        playbackError = error?.code == URLError.notConnectedToInternet.rawValue
          ? TrackPlayerError.PlaybackError.notConnectedToInternet
          : TrackPlayerError.PlaybackError.playbackFailed
      }
    }
  }

  func audioDidStart() {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      state = .playing
    }
  }

  func avItemDidUpdatePlaybackLikelyToKeepUp(_ playbackLikelyToKeepUp: Bool) {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      if playbackLikelyToKeepUp, state != .playing {
        state = .ready
      }
    }
  }

  // MARK: - Queue Validation

  private func throwIfQueueEmpty() throws {
    if tracks.isEmpty {
      throw TrackPlayerError.QueueError.empty
    }
  }

  private func throwIfIndexInvalid(
    index: Int,
    name: String = "index",
    min: Int? = nil,
    max: Int? = nil
  ) throws {
    guard index >= (min ?? 0), (max ?? tracks.count) > index else {
      throw TrackPlayerError.QueueError.invalidIndex(
        index: index,
        message: "\(name) must be non-negative and less than \(tracks.count)"
      )
    }
  }

  // MARK: - Queue Methods

  /**
   Replace the current track with a new one. If there is no current track, it is equivalent to calling `add(track:)`, `skipTo(trackIndex)`.

   - parameter track: The track to set as the new current track.
   */
  private func replaceCurrentTrackWith(_ track: Track) {
    assertMainThread()
    if currentIndex == -1 {
      tracks.append(track)
      currentIndex = 0
    } else {
      tracks[currentIndex] = track
    }
    handleCurrentTrackChanged()
  }

  /**
   Add tracks to the queue.

   - parameter tracks: The tracks to add to the queue.
   - parameter playWhenReady: Optional, whether to start playback when the track is ready.
   */
  public func add(_ tracks: [Track], playWhenReady: Bool? = nil) {
    handlePlayWhenReady(playWhenReady) {
      add(tracks)
    }
  }

  private func add(_ newTracks: [Track]) {
    assertMainThread()
    guard !newTracks.isEmpty else { return }
    let wasEmpty = tracks.isEmpty
    tracks.append(contentsOf: newTracks)
    if wasEmpty {
      currentIndex = 0
      handleCurrentTrackChanged()
    }
  }

  public func add(_ tracks: [Track], at index: Int) throws {
    assertMainThread()
    guard !tracks.isEmpty else { return }
    guard index >= 0, self.tracks.count >= index else {
      throw TrackPlayerError.QueueError.invalidIndex(
        index: index,
        message: "Index to insert at has to be non-negative and equal to or smaller than the number of tracks: (\(self.tracks.count))"
      )
    }
    let wasEmpty = self.tracks.isEmpty
    // Correct index when tracks were inserted in front of it:
    if self.tracks.count > 1, currentIndex >= index {
      currentIndex += tracks.count
    }
    self.tracks.insert(contentsOf: tracks, at: index)
    if wasEmpty {
      currentIndex = 0
      handleCurrentTrackChanged()
    }
  }

  /**
   Step to the next track in the queue.
   */
  public func next() {
    skipBy(1, wrap: repeatMode == .queue)
  }

  /**
   Step to the previous track in the queue.
   */
  public func previous() {
    skipBy(-1, wrap: repeatMode == .queue)
  }

  private func skipBy(_ delta: Int, wrap: Bool) {
    assertMainThread()
    guard currentTrack != nil, !tracks.isEmpty else { return }

    if tracks.count == 1 {
      if wrap, playWhenReady {
        replay()
      }
      return
    }

    var index = currentIndex + delta
    if wrap {
      index = (index + tracks.count) % tracks.count
    }
    let newIndex = max(0, min(tracks.count - 1, index))

    if newIndex != currentIndex {
      currentIndex = newIndex
      handleCurrentTrackChanged()
    }
  }

  /**
   Remove a track from the queue.

   - parameter index: The index of the track to remove.
   - throws: `TrackPlayerError.QueueError`
   */
  public func remove(_ index: Int) throws {
    assertMainThread()
    try throwIfQueueEmpty()
    try throwIfIndexInvalid(index: index)
    let result = tracks.remove(at: index)
    if index == currentIndex {
      currentIndex = tracks.count > 0 ? currentIndex % tracks.count : -1
      handleCurrentTrackChanged()
    } else if index < currentIndex {
      currentIndex -= 1
    }
  }

  /**
   Skip to a certain track in the queue.

   - parameter index: The index of the track to skip to.
   - parameter playWhenReady: Optional, whether to start playback when the track is ready.
   - throws: `TrackPlayerError`
   */
  public func skipTo(_ index: Int, playWhenReady: Bool? = nil) throws {
    try handlePlayWhenReady(playWhenReady) {
      if index == currentIndex {
        seekTo(0)
      } else {
        try skipTo(index)
      }
    }
  }

  private func skipTo(_ index: Int) throws {
    assertMainThread()
    try throwIfQueueEmpty()
    try throwIfIndexInvalid(index: index)

    if index == currentIndex {
      if playWhenReady {
        replay()
      }
    } else {
      currentIndex = index
      handleCurrentTrackChanged()
    }
  }

  /**
   Move a track in the queue from one position to another.

   - parameter fromIndex: The index of the track to move.
   - parameter toIndex: The index to move the track to.
   - throws: `TrackPlayerError.QueueError`
   */
  public func move(fromIndex: Int, toIndex: Int) throws {
    assertMainThread()
    try throwIfQueueEmpty()
    try throwIfIndexInvalid(index: fromIndex, name: "fromIndex")
    try throwIfIndexInvalid(index: toIndex, name: "toIndex", max: Int.max)

    let track = tracks.remove(at: fromIndex)
    tracks.insert(track, at: min(tracks.count, toIndex))
    if fromIndex == currentIndex {
      currentIndex = toIndex
      handleCurrentTrackChanged()
    }
  }

  /**
   Remove all upcoming tracks, those returned by `next()`
   */
  public func removeUpcomingTracks() {
    assertMainThread()
    guard !tracks.isEmpty else { return }
    let nextIndex = currentIndex + 1
    guard nextIndex < tracks.count else { return }
    tracks.removeSubrange(nextIndex ..< tracks.count)
  }

  /**
   Removes all tracks from queue
   */
  private func clearTracks() {
    assertMainThread()
    guard currentIndex != -1 else { return }
    currentIndex = -1
    tracks.removeAll()
    handleCurrentTrackChanged()
  }

  func replay() {
    seekTo(0) { [weak self] succeeded in
      if succeeded {
        self?.play()
      }
    }
  }

  func handleCurrentTrackChanged() {
    // Reset end-of-track sleep timer when track changes
    sleepTimerManager.onTrackChanged()

    let lastPosition = currentTime
    let shouldContinuePlayback = playWhenReady
    if let currentTrack {
      // Ensure playWhenReady is set before loading to preserve playback state
      playWhenReady = shouldContinuePlayback

      // Update now playing info
      if automaticallyUpdateNowPlayingInfo {
        // Reset playback values without updating, because that will happen in
        // the loadNowPlayingMetaValues call straight after:
        nowPlayingInfoController.setWithoutUpdate(keyValues: [
          MediaItemProperty.duration(nil),
          NowPlayingInfoProperty.playbackRate(nil),
          NowPlayingInfoProperty.elapsedPlaybackTime(nil),
        ])
        loadNowPlayingMetaValues()
      }

      // Enable remote commands
      enableRemoteCommands(remoteCommands)

      // Load the track - resolve media URL first if resolver is configured
      guard let src = currentTrack.src else {
        logger.error("Failed to load track - no src")
        logger.error("  track.title: \(currentTrack.title)")
        logger.error("  track.url: \(currentTrack.url ?? "nil")")
        clearCurrentAVItem()
        playbackError = TrackPlayerError.PlaybackError.invalidSourceUrl("nil")
        return
      }

      logger.debug("Loading track: \(currentTrack.title)")
      logger.debug("  track.url: \(currentTrack.url ?? "nil")")
      logger.debug("  track.src: \(src)")

      // Check if it's already a full URL (http/https) or local file
      if src.hasPrefix("http://") || src.hasPrefix("https://") || src.hasPrefix("file://") {
        // Already a full URL, use directly (may still need transform for headers)
        resolveAndLoadMedia(src: src, track: currentTrack)
      } else {
        // Relative path - needs media URL resolution
        resolveAndLoadMedia(src: src, track: currentTrack)
      }
    } else {
      let playbackWasActive = playbackActive
      unloadAVPlayer()
      nowPlayingInfoController.clear()
    }

    let eventData = PlaybackActiveTrackChangedEvent(
      lastIndex: lastIndex == -1 ? nil : Double(lastIndex),
      lastTrack: lastTrack,
      lastPosition: lastPosition,
      index: currentIndex == -1 ? nil : Double(currentIndex),
      track: currentTrack
    )
    callbacks?.playerDidChangeActiveTrack(eventData)
    lastTrack = currentTrack
    lastIndex = currentIndex
  }

  /// Resolves the media URL (applying transform if configured) and loads the player
  private func resolveAndLoadMedia(src: String, track: Track) {
    // If we have a resolver, use it asynchronously
    if let resolver = mediaUrlResolver {
      Task { @MainActor in
        // Check that this track is still the current one
        guard self.currentTrack?.src == track.src else {
          self.logger.debug("Track changed during media resolution, skipping load")
          return
        }

        let resolved = await resolver(src)
        self.logger.debug("  resolved URL: \(resolved.url)")
        if let headers = resolved.headers {
          self.logger.debug("  headers: \(headers)")
        }
        if let userAgent = resolved.userAgent {
          self.logger.debug("  userAgent: \(userAgent)")
        }

        self.loadMediaWithResolvedUrl(resolved, track: track)
      }
    } else {
      // No resolver, use src directly
      let resolved = MediaResolvedUrl(url: src, headers: nil, userAgent: nil)
      loadMediaWithResolvedUrl(resolved, track: track)
    }
  }

  /// Loads the AVPlayer with a resolved media URL
  private func loadMediaWithResolvedUrl(_ resolved: MediaResolvedUrl, track: Track) {
    assertMainThread()

    guard let mediaUrl = URL(string: resolved.url) else {
      logger.error("Invalid media URL: \(resolved.url)")
      playbackError = TrackPlayerError.PlaybackError.invalidSourceUrl(resolved.url)
      return
    }

    // Build URL options with headers if provided
    var options: [String: Any] = [:]
    if let headers = resolved.headers, !headers.isEmpty {
      options["AVURLAssetHTTPHeaderFieldsKey"] = headers
    }

    let isLocalFile = mediaUrl.isFileURL
    url = isLocalFile ? URL(fileURLWithPath: mediaUrl.path) : mediaUrl
    urlOptions = options.isEmpty ? nil : options

    logger.debug("  final playbackUrl: \(mediaUrl.absoluteString)")
    logger.debug("  isLocalFile: \(isLocalFile)")

    loadAVPlayer()
  }
}
