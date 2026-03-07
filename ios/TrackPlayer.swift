@preconcurrency import AVFoundation
import Foundation
import MediaPlayer
import NitroModules
import os.log

@MainActor
class TrackPlayer {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "TrackPlayer")

  let nowPlayingInfoController: NowPlayingInfoController
  let remoteCommandController: RemoteCommandController
  let sleepTimerManager = SleepTimerManager()
  private let retryManager = RetryManager()
  private var pendingRetryTask: Task<Void, Never>?
  private weak var callbacks: TrackPlayerCallbacks?
  private var lastIndex: Int = -1
  private var lastTrack: Track?

  /// Retry configuration for load errors (network failures, timeouts, etc.)
  var retryConfig: Variant_Bool_RetryConfig? {
    didSet {
      retryManager.updatePolicy(from: retryConfig)
    }
  }

  /// Network monitor for accelerating retries when connectivity is restored.
  /// When set, retries will trigger immediately when network comes back online
  /// instead of waiting for the full exponential backoff delay.
  weak var networkMonitor: NetworkMonitor? {
    didSet {
      retryManager.networkMonitor = networkMonitor
    }
  }

  /// Handles media URL resolution, asset creation, and async loading.
  let mediaLoader = MediaLoader()

  /// Handles Now Playing metadata and artwork updates.
  let nowPlayingUpdater: NowPlayingUpdater

  // MARK: - Queue Manager

  let queue = QueueManager()

  // MARK: - Queue Forwarding Properties

  var tracks: [Track] { queue.tracks }
  var currentIndex: Int { queue.currentIndex }
  var currentTrack: Track? { queue.currentTrack }
  var queueSourcePath: String? { queue.queueSourcePath }
  var nextTracks: [Track] { queue.nextTracks }
  var previousTracks: [Track] { queue.previousTracks }
  private var isLastInPlaybackOrder: Bool { queue.isLastInPlaybackOrder }

  /// The repeat mode for the queue player.
  var repeatMode: RepeatMode {
    get { queue.repeatMode }
    set {
      guard queue.repeatMode != newValue else { return }
      queue.repeatMode = newValue
      remoteCommandController.updateRepeatMode(newValue)
      callbacks?.playerDidChangeRepeatMode(
        RepeatModeChangedEvent(repeatMode: newValue)
      )
    }
  }

  /// Whether shuffle mode is enabled.
  /// When enabled, next/previous traverse the shuffle order instead of sequential order.
  var shuffleEnabled: Bool {
    get { queue.shuffleEnabled }
    set {
      guard queue.shuffleEnabled != newValue else { return }
      queue.shuffleEnabled = newValue
      remoteCommandController.updateShuffleMode(newValue)
      callbacks?.playerDidChangeShuffleEnabled(newValue)
    }
  }

  // MARK: - AVPlayer Properties (from AVPlayerWrapper)

  private var avPlayer = AVPlayer()

  private lazy var playerObserver: PlayerStateObserver = .init(
    onStatusChange: { [weak self] status in
      self?.avPlayerStatusDidChange(status)
    },
    onTimeControlStatusChange: { [weak self] status in
      self?.avPlayerDidChangeTimeControlStatus(status)
    },
  )

  private lazy var playerTimeObserver: PlayerTimeObserver = .init(
    periodicObserverTimeInterval: CMTime(seconds: 1, preferredTimescale: 1000),
    onAudioDidStart: { [weak self] in
      self?.audioDidStart()
    },
    onSecondElapsed: { [weak self] seconds in
      self?.nowPlayingUpdater.setCurrentTime(seconds: seconds)
    },
  )

  private lazy var playerItemNotificationObserver: PlayerItemNotificationObserver = .init(
    onDidPlayToEndTime: { [weak self] in
      self?.handleTrackDidPlayToEndTime()
    },
    onFailedToPlayToEndTime: { [weak self] error in
      self?.handleItemFailedToPlayToEndTime(error: error)
    },
  )

  private lazy var playerItemObserver: PlayerItemPropertyObserver = .init(
    onDurationUpdate: { [weak self] duration in
      self?.callbacks?.playerDidUpdateDuration(duration)
    },
    onPlaybackLikelyToKeepUpUpdate: { [weak self] isLikely in
      self?.avItemDidUpdatePlaybackLikelyToKeepUp(isLikely)
    },
    onStatusChange: { [weak self] status, error in
      self?.avItemStatusDidChange(status, error: error)
    },
    onTimedMetadataReceived: { [weak self] groups in
      self?.callbacks?.playerDidReceiveTimedMetadata(groups)
    },
  )

  private lazy var progressUpdateManager: PlaybackProgressUpdateManager =
    PlaybackProgressUpdateManager { [weak self] in
      guard let self, currentIndex >= 0 else { return }
      let progressEvent = PlaybackProgressUpdatedEvent(
        track: Double(currentIndex),
        position: currentTime,
        duration: duration,
        buffered: bufferedPosition,
      )
      callbacks?.playerDidUpdateProgress(progressEvent)
    }

  lazy var playingStateManager: PlayingStateManager = PlayingStateManager { [weak self] state in
    self?.callbacks?.playerDidChangePlayingState(state)
  }

  private let loadSeekCoordinator = LoadSeekCoordinator()
  private(set) var playbackError: TrackPlayerError.PlaybackError?

  private(set) var lastPlayerTimeControlStatus: AVPlayer.TimeControlStatus = .paused

  func getPlayback() -> Playback {
    Playback(state: state, error: playbackError?.toNitroError())
  }

  func getRepeatMode() -> RepeatMode {
    repeatMode
  }

  func setRepeatMode(_ mode: RepeatMode) {
    repeatMode = mode
  }

  /**
   Controls the time pitch algorithm applied to each track loaded into the player.
   If the loaded `AudioItem` conforms to `TimePitcher`-protocol this will be overriden.
   */
  var audioTimePitchAlgorithm: AVAudioTimePitchAlgorithm = .timeDomain

  /**
   Default remote commands to use for each playing track
   */
  var remoteCommands: [RemoteCommand] = [] {
    didSet {
      enableRemoteCommands(remoteCommands)
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

  private(set) var state: PlaybackState = .none

  // MARK: - Playback State Machine

  private func transition(_ event: PlaybackEvent) {
    guard let newState = nextState(from: state, on: event) else { return }

    // Allow error-to-error transitions to update the error and emit callbacks,
    // even though the state enum value doesn't change.
    if newState == state, case .errorOccurred(let error) = event {
      playbackError = error
      callbacks?.playerDidChangePlayback(
        Playback(state: state, error: playbackError?.toNitroError()),
      )
      callbacks?.playerDidError(
        PlaybackErrorEvent(error: playbackError?.toNitroError()),
      )
      return
    }

    guard newState != state else { return }
    let oldState = state
    state = newState
    applySideEffects(old: oldState, new: newState, event: event)
    emitStateChange(old: oldState, new: newState)
  }

  /// Determines the next state for a given event, or `nil` to suppress the transition.
  ///
  /// Guards here are **state-related** (e.g., "only from .loading"). Context-related
  /// guards (e.g., `asset != nil`, `nearTrackEnd`, `!playWhenReady`) live at the call
  /// site and decide whether to fire the event at all.
  private func nextState(from current: PlaybackState, on event: PlaybackEvent) -> PlaybackState? {
    switch event {
    case .stopped:             return .stopped
    case .trackLoading:        return .loading
    case .trackUnloaded:       return .none
    case .trackEndedNaturally: return .ended
    case .avPlayerWaiting:     return .buffering
    case .avPlayerPlaying:     return .playing
    case .audioFrameDecoded:   return .playing
    case .errorOccurred:       return .error

    case .loadSeekCompleted:
      guard current == .loading else { return nil }
      return .ready

    case .avPlayerPaused(let hasAsset):
      guard current != .stopped else { return nil }
      if !hasAsset { return .none }
      guard current != .error else { return nil }
      return .paused

    case .bufferingSufficient:
      guard current != .playing else { return nil }
      return .ready
    }
  }

  private func applySideEffects(old: PlaybackState, new: PlaybackState, event: PlaybackEvent) {
    // Error lifecycle
    if old == .error, new != .error {
      playbackError = nil
    }
    if case .errorOccurred(let error) = event {
      playbackError = error
    }

    // State-specific effects
    switch new {
    case .ready:
      setTimePitchingAlgorithmForCurrentItem()
      if playWhenReady { startPlayback() }
    case .loading:
      setTimePitchingAlgorithmForCurrentItem()
    default: break
    }

    // Now Playing updates for active states
    switch new {
    case .ready, .loading, .playing, .paused:
      nowPlayingUpdater.updatePlaybackValues(duration: duration, rate: rate, currentTime: currentTime)
      nowPlayingUpdater.updatePlaybackState(playWhenReady: playWhenReady)
    default: break
    }

    progressUpdateManager.onPlaybackStateChanged(new)
    playingStateManager.update(playWhenReady: playWhenReady, state: new)
  }

  private func emitStateChange(old: PlaybackState, new: PlaybackState) {
    // Playback state change — always emitted
    callbacks?.playerDidChangePlayback(
      Playback(state: new, error: playbackError?.toNitroError()),
    )

    // Error callback — emitted when entering or leaving error state
    // (preserves current ordering: playerDidChangePlayback fires first)
    if new == .error || (old == .error && new != .error) {
      callbacks?.playerDidError(
        PlaybackErrorEvent(error: playbackError?.toNitroError()),
      )
    }

    // Queue ended — when playback ends on the last track
    if new == .ended, isLastInPlaybackOrder {
      callbacks?.playerDidEndQueue(
        PlaybackQueueEndedEvent(track: Double(currentIndex), position: currentTime),
      )
    }
  }

  var playbackActive: Bool {
    switch state {
    case .none, .stopped, .ended, .error:
      false
    default: true
    }
  }

  var reasonForWaitingToPlay: AVPlayer.WaitingReason? {
    avPlayer.reasonForWaitingToPlay
  }

  // MARK: - Getters from AVPlayerWrapper

  /**
   The elapsed playback time of the current track.
   */
  var currentTime: Double {
    let seconds = avPlayer.currentTime().seconds
    return seconds.isNaN ? 0 : seconds
  }

  /**
   The duration of the current track.
   */
  var duration: Double {
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
  var bufferedPosition: Double {
    avPlayer.currentItem?.loadedTimeRanges.last?.timeRangeValue.end.seconds ?? 0
  }

  /**
   The current state of the underlying `TrackPlayer`.
   */
  var playerState: PlaybackState {
    state
  }

  // MARK: - Setters for AVPlayerWrapper

  /**
   Whether the player should start playing automatically when the track is ready.
   */
  var playWhenReady: Bool = false {
    didSet {
      if playWhenReady == true, state == .error || state == .stopped {
        reload(startFromCurrentTime: state == .error)
      }
      if state != .loading {
        if playWhenReady {
          startPlayback()
        } else {
          pausePlayback()
        }
      }

      if oldValue != playWhenReady {
        callbacks?.playerDidChangePlayWhenReady(playWhenReady)
        playingStateManager.update(playWhenReady: playWhenReady, state: state)
      }
    }
  }

  /**
   The amount of milliseconds to be buffered by the player. Default value is 0, this means the AVPlayer will choose an appropriate level of buffering. Setting `bufferDuration` to larger than zero automatically disables `automaticallyWaitsToMinimizeStalling`. Setting it back to zero automatically enables `automaticallyWaitsToMinimizeStalling`.

   [Read more from Apple Documentation](https://developer.apple.com/documentation/avfoundation/avplayeritem/1643630-preferredforwardbufferduration)
   */
  var bufferDuration: Double = 0 {
    didSet {
      avPlayer.automaticallyWaitsToMinimizeStalling = bufferDuration == 0
      mediaLoader.bufferDuration = bufferDuration
    }
  }

  /**
   Indicates whether the player should automatically delay playback in order to minimize stalling. Setting this to true will also set `bufferDuration` back to `0`.

   [Read more from Apple Documentation](https://developer.apple.com/documentation/avfoundation/avplayer/1643482-automaticallywaitstominimizestal)
   */
  var automaticallyWaitsToMinimizeStalling: Bool {
    get { avPlayer.automaticallyWaitsToMinimizeStalling }
    set {
      if newValue {
        bufferDuration = 0
      }
      avPlayer.automaticallyWaitsToMinimizeStalling = newValue
    }
  }

  var volume: Float {
    get { avPlayer.volume }
    set { avPlayer.volume = newValue }
  }

  var isMuted: Bool {
    get { avPlayer.isMuted }
    set { avPlayer.isMuted = newValue }
  }

  var rate: Float = 1.0 {
    didSet {
      avPlayer.rate = rate
      nowPlayingUpdater.updatePlaybackValues(duration: duration, rate: rate, currentTime: currentTime)
    }
  }

  // MARK: - Init

  init(
    nowPlayingInfoController: NowPlayingInfoController = NowPlayingInfoController(),
    callbacks: TrackPlayerCallbacks? = nil,
  ) {
    self.nowPlayingInfoController = nowPlayingInfoController
    nowPlayingUpdater = NowPlayingUpdater(nowPlayingInfoController: nowPlayingInfoController)
    remoteCommandController = RemoteCommandController(callbacks: callbacks)
    self.callbacks = callbacks
    queue.delegate = self
    mediaLoader.delegate = self

    // Configure sleep timer
    sleepTimerManager.onComplete = { [weak self] in
      self?.pause()
    }

    // Configure retry manager
    retryManager.shouldRetry = { [weak self] in
      self?.playWhenReady ?? false
    }
    retryManager.onRetry = { [weak self] startFromCurrentTime in
      self?.reload(startFromCurrentTime: startFromCurrentTime)
    }

    // Handle command center changes when MPNowPlayingSession is created/destroyed (iOS 16+)
    // This callback is guaranteed to be called on the main thread by NowPlayingInfoController
    nowPlayingInfoController.onRemoteCommandCenterChanged = { [weak self] newCommandCenter in
      MainActor.assumeIsolated {
        self?.remoteCommandController.switchCommandCenter(newCommandCenter)
      }
    }

    setupAVPlayer()
  }

  // MARK: - Player Actions

  /**
   Will replace the current track with a new one and load it into the player.

   - parameter track: The Track to replace the current track.
   - parameter playWhenReady: Optional, whether to start playback when the track is ready.
   */
  func load(_ track: Track, playWhenReady: Bool? = nil) {
    handlePlayWhenReady(playWhenReady) {
      if queue.currentIndex == -1 {
        let changed = queue.add([track], initialIndex: 0)
        if changed { handleCurrentTrackChanged() }
      } else {
        queue.replace(queue.currentIndex, track)
        handleCurrentTrackChanged()
      }
    }
  }

  /**
   Toggle playback status.
   */
  func togglePlaying() {
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
  func play() {
    playWhenReady = true
  }

  /**
   Pause playback
   */
  func pause() {
    playWhenReady = false
  }

  /**
   Toggle playback between play and pause
   */
  func togglePlayback() {
    playWhenReady = !playWhenReady
  }

  /**
   Stop playback
   */
  func stop() {
    transition(.stopped)
    if currentTrack?.live != true {
      seekTo(0)
    }
    playWhenReady = false
  }

  /**
   Reload the current track.
   */
  func reload(startFromCurrentTime: Bool) {
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
  func seekTo(_ seconds: TimeInterval) {
    seekTo(seconds, completion: { _ in })
  }

  /**
   Seek to a specific time in the track with a completion handler.

   - parameter seconds: The time to seek to.
   - parameter completion: Called when the seek operation completes. The Bool parameter indicates whether the seek finished successfully (true) or was interrupted/deferred (false).
   */
  func seekTo(_ seconds: TimeInterval, completion: @escaping @MainActor (Bool) -> Void) {
    // If a track is currently being loaded asynchronously, defer the seek until it's ready.
    if state == .loading {
      loadSeekCoordinator.capture(position: seconds)
      // The coordinator executes the deferred seek once the item is ready; replay() is the
      // only caller that uses completion, and it's never called during loading.
      completion(false)
    } else if avPlayer.currentItem != nil {
      let time = CMTime(seconds: seconds, preferredTimescale: 1000)
      let seekSeconds = seconds
      avPlayer
        .seek(to: time, toleranceBefore: CMTime.zero, toleranceAfter: CMTime.zero) { [weak self] finished in
          Task { @MainActor in
            self?.handleSeekCompleted(to: Double(seekSeconds), didFinish: finished)
            completion(finished)
          }
        }
    } else {
      // No track loaded and not loading - seek fails immediately
      completion(false)
    }
  }

  /**
   Seek by relative a time offset in the track.
   */
  func seekBy(_ offset: TimeInterval) {
    // Calculate the target time based on current state
    let targetTime: TimeInterval
    if state == .loading {
      // If loading, offset from pending seek (or 0 if no pending seek)
      targetTime = (loadSeekCoordinator.pendingTime ?? 0) + offset
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

  func clear() {
    let changed = queue.clear()
    if changed { handleCurrentTrackChanged() }
    unloadAVPlayer()
    nowPlayingInfoController.unlinkPlayer()
    nowPlayingInfoController.clear()
  }

  /// Tears down the player completely, stopping audio and removing all remote command targets.
  /// Called when the HybridAudioBrowser is being replaced (e.g., JS runtime reload).
  func destroy() {
    clear()
    remoteCommandController.disableAll()
  }

  // MARK: - Private

  private func setTimePitchingAlgorithmForCurrentItem() {
    // Use player's default pitch algorithm (per-track pitch control not in Nitro API)
    avPlayer.currentItem?.audioTimePitchAlgorithm = audioTimePitchAlgorithm
  }

  // MARK: - AVPlayer Management Methods (from AVPlayerWrapper)

  /// Starts playback at the configured rate
  private func startPlayback() {
    avPlayer.play()
    if rate != 1.0 {
      avPlayer.rate = rate
    }
  }

  /// Pauses playback
  private func pausePlayback() {
    avPlayer.pause()
  }

  private func clearCurrentAVItem() {
    stopObservingAVPlayerItem()
    mediaLoader.clearAsset()
    loadSeekCoordinator.reset()
    avPlayer.replaceCurrentItem(with: nil)
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
    // Clear directly rather than via transition() — the caller (loadAVPlayer)
    // immediately follows with transition(.trackLoading) which handles the
    // .error → .loading transition and its side effects.
    playbackError = nil
    playerTimeObserver.unregisterForBoundaryTimeEvents()
    playerTimeObserver.unregisterForPeriodicEvents()
    playerObserver.stopObserving()
    stopObservingAVPlayerItem()
    clearCurrentAVItem()

    // Unlink old player before creating new one
    nowPlayingInfoController.unlinkPlayer()

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

    // Link AVPlayer to NowPlayingInfoController for automatic publishing on iOS 16+
    nowPlayingInfoController.linkPlayer(avPlayer)

    // Apply initial playback state
    if playWhenReady {
      startPlayback()
    } else {
      // Ensure defaultRate is set for when playback starts later
      if #available(iOS 16.0, *) {
        avPlayer.defaultRate = rate
      }
    }
  }

  func loadAVPlayer() {
    if state == .error {
      recreateAVPlayer()
    } else {
      mediaLoader.cancelAll()
      stopObservingAVPlayerItem()
      pausePlayback()
      mediaLoader.clearAsset()
    }
    transition(.trackLoading)
    mediaLoader.loadAsset()
  }

  func unloadAVPlayer() {
    clearCurrentAVItem()
    transition(.trackUnloaded)
  }

  // MARK: - Internal Event Handlers

  /**
   Sets the progress update interval.
   - Parameter interval: The interval in seconds, or nil to disable progress updates
   */
  func setProgressUpdateInterval(_ interval: TimeInterval?) {
    progressUpdateManager.setUpdateInterval(interval)
  }

  func handleSeekCompleted(to seconds: Double, didFinish: Bool) {
    // If this was a deferred load-seek, transition out of .loading state
    // (unless a new seek was queued while this one was in-flight)
    if loadSeekCoordinator.seekDidComplete(on: avPlayer, delegate: self), state == .loading {
      logger.debug("[loadSeek] seek landed at \(seconds)s (finished=\(didFinish)) → .ready")
      transition(.loadSeekCompleted)
    }
    nowPlayingUpdater.setCurrentTime(seconds: seconds)
    callbacks?.playerDidCompleteSeek(position: seconds, didFinish: didFinish)
  }

  func handleTrackDidPlayToEndTime() {
    // Check if sleep timer should trigger on track end
    sleepTimerManager.onTrackPlayedToEnd()

    if repeatMode == .track {
      replay()
    } else if repeatMode == .queue || !isLastInPlaybackOrder {
      next()
    } else {
      transition(.trackEndedNaturally)
    }
  }

  // MARK: - Observer Callbacks

  func avPlayerDidChangeTimeControlStatus(_ status: AVPlayer.TimeControlStatus) {
    // During loading, ignore stale timeControlStatus changes from old items.
    // State transitions during loading are managed by MediaLoader
    // and avItemDidUpdatePlaybackLikelyToKeepUp.
    if state == .loading { return }

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
      } else if mediaLoader.asset == nil, currentState != .stopped {
        transition(.avPlayerPaused(hasAsset: false))
      } else if currentState != .error, currentState != .stopped {
        // Only update state, never modify playWhenReady
        // playWhenReady represents user intent and should only change via explicit user actions
        if !playWhenReady {
          transition(.avPlayerPaused(hasAsset: true))
        }
        // If playWhenReady is true, this is likely buffering/seeking - don't change state
      }
    case .waitingToPlayAtSpecifiedRate:
      if mediaLoader.asset != nil {
        transition(.avPlayerWaiting)
      }
    case .playing:
      transition(.avPlayerPlaying)
    @unknown default:
      break
    }
  }

  func avPlayerStatusDidChange(_ status: AVPlayer.Status) {
    if status == .failed {
      handlePlaybackFailure(error: avPlayer.currentItem?.error)
    }
  }

  /// Handles AVPlayerItem status changes - this catches errors that don't show up in AVPlayer.status
  func avItemStatusDidChange(_ status: AVPlayerItem.Status, error: Error?) {
    if status == .failed {
      let effectiveError = error ?? avPlayer.currentItem?.error
      handlePlaybackFailure(error: effectiveError)
    }
  }

  /// Common handler for playback failures from either AVPlayer or AVPlayerItem
  private func handlePlaybackFailure(error: Error?) {
    if let error {
      let nsError = error as NSError
      logger.error("Playback failure: domain=\(nsError.domain), code=\(nsError.code), localizedDescription=\(error.localizedDescription)")
    } else {
      logger.error("Playback failure with nil error")
    }

    // Try retry before setting error state
    if retryManager.isRetryable(error) {
      pendingRetryTask?.cancel()
      pendingRetryTask = Task {
        let retried = await retryManager.attemptRetry(startFromCurrentTime: true)
        if !retried {
          // Max retries exceeded or retry cancelled, surface error
          self.setPlaybackError(from: error)
        }
      }
      return
    }

    logger.warning("Error not retryable, surfacing playback_failed")
    setPlaybackError(from: error)
  }

  /// Sets the playback error from an Error, with appropriate classification
  private func setPlaybackError(from error: Error?) {
    let nsError = error as NSError?
    let playbackError: TrackPlayerError.PlaybackError =
      nsError?.code == URLError.notConnectedToInternet.rawValue
        ? .notConnectedToInternet
        : .playbackFailed
    transition(.errorOccurred(playbackError))
  }

  /// Handles AVPlayerItemFailedToPlayToEndTime notification with retry logic
  private func handleItemFailedToPlayToEndTime(error: Error?) {
    let effectiveError = error ?? avPlayer.currentItem?.error
    handlePlaybackFailure(error: effectiveError)
  }

  func audioDidStart() {
    // Don't override loading state - MediaLoader manages that transition
    if state == .loading { return }
    transition(.audioFrameDecoded)
  }

  func avItemDidUpdatePlaybackLikelyToKeepUp(_ playbackLikelyToKeepUp: Bool) {
    guard playbackLikelyToKeepUp else { return }

    // KVO callbacks are dispatched to MainActor asynchronously, so a stale callback
    // from a previous AVPlayerItem can arrive after the item was replaced/cleared.
    // Ignore these — there's nothing meaningful to do without a loaded item.
    guard avPlayer.currentItem != nil else { return }

    // Execute any pending seek that arrived after MediaLoader completed
    if loadSeekCoordinator.executeIfPending(on: avPlayer, delegate: self) {
      return
    }

    // Only transition to .ready if no seek is in-flight — otherwise
    // handleSeekCompleted will handle the transition once the seek lands.
    if !loadSeekCoordinator.shouldDeferReadyTransition, state != .playing {
      logger.debug("avItemDidUpdatePlaybackLikelyToKeepUp → .ready")
      transition(.bufferingSufficient)
    }
  }

  // MARK: - Queue Methods

  func replace(_ index: Int, _ track: Track) {
    queue.replace(index, track)
  }

  func setQueue(_ newTracks: [Track], initialIndex: Int = 0, playWhenReady: Bool? = nil, sourcePath: String? = nil) {
    guard !newTracks.isEmpty else {
      clear()
      return
    }
    handlePlayWhenReady(playWhenReady) {
      // setQueue always returns true (current track always changes)
      queue.setQueue(newTracks, initialIndex: initialIndex, sourcePath: sourcePath)
      handleCurrentTrackChanged()
    }
  }

  func add(_ tracks: [Track], initialIndex: Int? = nil, playWhenReady: Bool? = nil) {
    handlePlayWhenReady(playWhenReady) {
      let changed = queue.add(tracks, initialIndex: initialIndex ?? 0)
      if changed { handleCurrentTrackChanged() }
    }
  }

  func add(_ tracks: [Track], at index: Int) throws {
    let changed = try queue.addAt(tracks, at: index)
    if changed { handleCurrentTrackChanged() }
  }

  func next() {
    let result = queue.next()
    switch result {
    case .trackChanged:    handleCurrentTrackChanged()
    case .sameTrackReplay: if playWhenReady { replay() }
    case .noChange:        break
    }
  }

  func previous() {
    let result = queue.previous()
    switch result {
    case .trackChanged:    handleCurrentTrackChanged()
    case .sameTrackReplay: if playWhenReady { replay() }
    case .noChange:        break
    }
  }

  func remove(_ index: Int) throws {
    let changed = try queue.remove(index)
    if changed { handleCurrentTrackChanged() }
  }

  func skipTo(_ index: Int, playWhenReady: Bool? = nil) throws {
    try handlePlayWhenReady(playWhenReady) {
      if index == queue.currentIndex {
        seekTo(0)
      } else {
        // skipTo always returns .trackChanged (same-index is handled above)
        try queue.skipTo(index)
        handleCurrentTrackChanged()
      }
    }
  }

  func move(fromIndex: Int, toIndex: Int) throws {
    let changed = try queue.move(fromIndex: fromIndex, toIndex: toIndex)
    if changed { handleCurrentTrackChanged() }
  }

  func removeUpcomingTracks() {
    queue.removeUpcomingTracks()
  }

  func replay() {
    seekTo(0) { [weak self] succeeded in
      if succeeded { self?.play() }
    }
  }

  func handleCurrentTrackChanged() {
    // Reset end-of-track sleep timer when track changes
    sleepTimerManager.onTrackChanged()

    mediaLoader.cancelAll()
    loadSeekCoordinator.reset()

    // Cancel any pending retry and reset count when track changes
    pendingRetryTask?.cancel()
    pendingRetryTask = nil
    retryManager.reset()

    // Clear directly rather than via transition() — the code below immediately
    // follows with transition(.trackLoading) which handles the .error → .loading
    // transition and its side effects.
    if playbackError != nil {
      playbackError = nil
    }

    let lastPosition = currentTime
    let shouldContinuePlayback = playWhenReady
    if let currentTrack {
      // Cancel in-flight loading from previous track and clear old item
      // to prevent stale audio during async URL resolution.
      stopObservingAVPlayerItem()
      pausePlayback()
      avPlayer.replaceCurrentItem(with: nil)

      // Set loading state before playWhenReady so the setter's guard
      // prevents a no-op startPlayback() on the now-nil item.
      transition(.trackLoading)

      // Ensure playWhenReady is set before loading to preserve playback state
      playWhenReady = shouldContinuePlayback

      // Reset playback values without updating, because that will happen in
      // the nowPlayingUpdater.loadMetaValues call straight after:
      nowPlayingInfoController.setWithoutUpdate(keyValues: [
        MediaItemProperty.duration(nil),
        NowPlayingInfoProperty.playbackRate(nil),
        NowPlayingInfoProperty.elapsedPlaybackTime(nil),
      ])
      nowPlayingUpdater.loadMetaValues(for: currentTrack, rate: rate)

      // Validate source URL before handing off to MediaLoader
      guard let src = currentTrack.src else {
        logger.error("Failed to load track - no src")
        logger.error("  track.title: \(currentTrack.title)")
        logger.error("  track.url: \(currentTrack.url ?? "nil")")
        clearCurrentAVItem()
        transition(.errorOccurred(.invalidSourceUrl("nil")))
        return
      }

      logger.debug("Loading track: \(currentTrack.title)")
      logger.debug("  track.url: \(currentTrack.url ?? "nil")")
      logger.debug("  track.src: \(src)")

      mediaLoader.resolveAndLoad(src: src)
    } else {
      unloadAVPlayer()
      nowPlayingInfoController.clear()
    }

    let eventData = PlaybackActiveTrackChangedEvent(
      lastIndex: lastIndex == -1 ? nil : Double(lastIndex),
      lastTrack: lastTrack,
      lastPosition: lastPosition,
      index: currentIndex == -1 ? nil : Double(currentIndex),
      track: currentTrack,
    )
    callbacks?.playerDidChangeActiveTrack(eventData)
    lastTrack = currentTrack
    lastIndex = currentIndex
  }

}

// MARK: - QueueManagerDelegate

extension TrackPlayer: QueueManagerDelegate {
  func queueDidChangeTracks(_ tracks: [Track]) {
    callbacks?.playerDidChangeQueue(tracks)
  }
}

// MARK: - SeekCompletionHandler

extension TrackPlayer: SeekCompletionHandler {}

// MARK: - MediaLoaderDelegate

extension TrackPlayer: MediaLoaderDelegate {
  func mediaLoaderDidPrepareItem(_ item: AVPlayerItem) {
    nowPlayingInfoController.prepareItem(item)
    avPlayer.replaceCurrentItem(with: item)
    startObservingAVPlayerItem(item)
    if playWhenReady { startPlayback() }

    if !loadSeekCoordinator.executeIfPending(on: avPlayer, delegate: self) {
      if item.isPlaybackLikelyToKeepUp {
        avItemDidUpdatePlaybackLikelyToKeepUp(true)
      }
    }
  }

  func mediaLoaderDidFailWithRetryableError(_ error: Error) {
    if retryManager.isRetryable(error) {
      pendingRetryTask?.cancel()
      pendingRetryTask = Task {
        let retried = await retryManager.attemptRetry(startFromCurrentTime: false)
        if !retried {
          let nsError = error as NSError?
          let playbackError: TrackPlayerError.PlaybackError =
            nsError?.code == URLError.notConnectedToInternet.rawValue
              ? .notConnectedToInternet : .failedToLoadKeyValue
          self.transition(.errorOccurred(playbackError))
        }
      }
      return
    }
    transition(.errorOccurred(.failedToLoadKeyValue))
  }

  func mediaLoaderDidFailWithUnplayableTrack() {
    transition(.errorOccurred(.trackWasUnplayable))
  }

  func mediaLoaderDidFailWithError(_ error: TrackPlayerError.PlaybackError) {
    transition(.errorOccurred(error))
  }

  func mediaLoaderDidReceiveCommonMetadata(_ items: [AVMetadataItem]) {
    callbacks?.playerDidReceiveCommonMetadata(items)
  }

  func mediaLoaderDidReceiveChapterMetadata(_ groups: [AVTimedMetadataGroup]) {
    callbacks?.playerDidReceiveChapterMetadata(groups)
  }

  func mediaLoaderDidReceiveTimedMetadata(_ groups: [AVTimedMetadataGroup]) {
    callbacks?.playerDidReceiveTimedMetadata(groups)
  }
}
