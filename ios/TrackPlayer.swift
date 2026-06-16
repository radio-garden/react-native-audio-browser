@preconcurrency import AVFoundation
import Foundation
import MediaPlayer
import NitroModules
import os.log

@MainActor
class TrackPlayer {
  let logger = Logger(subsystem: "com.audiobrowser", category: "TrackPlayer")

  // MARK: - Coordinator

  let coordinator: PlaybackCoordinator

  // MARK: - Dependencies

  let nowPlayingInfoController: NowPlayingInfoController
  let remoteCommandController: RemoteCommandController
  private let retryManager = RetryManager()

  /// Retry configuration for load errors (network failures, timeouts, etc.)
  var retryConfig: Variant_Bool_RetryConfig? {
    didSet {
      retryManager.updatePolicy(from: retryConfig)
    }
  }

  /// Network monitor for accelerating retries when connectivity is restored.
  weak var networkMonitor: NetworkMonitor? {
    didSet {
      retryManager.networkMonitor = networkMonitor
    }
  }

  /// Handles media URL resolution, asset creation, and async loading.
  let mediaLoader = MediaLoader()

  /// Handles Now Playing metadata and artwork updates.
  let nowPlayingUpdater: NowPlayingUpdater

  // MARK: - AVPlayer Properties

  var avPlayer = AVPlayer()

  private lazy var playerObserver: PlayerStateObserver = .init(
    onStatusChange: { [weak self] status in
      self?.avPlayerStatusDidChange(status)
    },
    onTimeControlStatusChange: { [weak self] status in
      self?.avPlayerDidChangeTimeControlStatus(status)
    },
  )

  private lazy var playerTimeObserver: PlayerTimeObserver = .init(
    onAudioDidStart: { [weak self] in
      self?.coordinator.audioDidStart()
    },
  )

  private lazy var playerItemNotificationObserver: PlayerItemNotificationObserver = .init(
    onDidPlayToEndTime: { [weak self] in
      self?.coordinator.handleTrackDidPlayToEndTime()
    },
    onFailedToPlayToEndTime: { [weak self] error in
      let effectiveError = error ?? self?.avPlayer.currentItem?.error
      self?.coordinator.errorHandler.handleError(effectiveError, context: .playback)
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

  // MARK: - Callbacks

  weak var callbacks: TrackPlayerCallbacks? {
    didSet {
      coordinator.callbacks = callbacks
    }
  }

  // MARK: - Coordinator Forwarding Properties

  var state: PlaybackState { coordinator.state }
  var playbackError: TrackPlayerError.PlaybackError? {
    get { coordinator.playbackError }
    set { coordinator.playbackError = newValue }
  }

  var lastIndex: Int {
    get { coordinator.lastIndex }
    set { coordinator.lastIndex = newValue }
  }

  var lastTrack: Track? {
    get { coordinator.lastTrack }
    set { coordinator.lastTrack = newValue }
  }

  var tracks: [Track] { coordinator.tracks }
  var currentIndex: Int { coordinator.currentIndex }
  var currentTrack: Track? { coordinator.currentTrack }
  var queueSourcePath: String? { coordinator.queueSourcePath }
  var nextTracks: [Track] { coordinator.nextTracks }
  var previousTracks: [Track] { coordinator.previousTracks }
  var isLastInPlaybackOrder: Bool { coordinator.isLastInPlaybackOrder }
  var playbackActive: Bool { coordinator.playbackActive }
  var sleepTimerManager: SleepTimerManager { coordinator.sleepTimerManager as! SleepTimerManager }
  var queue: QueueManager { coordinator.queue }
  var loadSeekCoordinator: LoadSeekCoordinator { coordinator.loadSeekCoordinator }
  var playingStateManager: PlayingStateManager { coordinator.playingStateManager }
  var errorHandler: PlaybackErrorHandler { coordinator.errorHandler }

  var repeatMode: RepeatMode {
    get { coordinator.repeatMode }
    set { coordinator.repeatMode = newValue }
  }

  var shuffleEnabled: Bool {
    get { coordinator.shuffleEnabled }
    set { coordinator.shuffleEnabled = newValue }
  }

  var playWhenReady: Bool {
    get { coordinator.playWhenReady }
    set { coordinator.playWhenReady = newValue }
  }

  /// Time playback must have progressed before a buffer dip counts as a stall, so the initial
  /// connect / a seek doesn't read as one. iOS has no native rebuffer-vs-initial signal.
  private static let stallGraceSeconds: TimeInterval = 0.5

  /// True while ongoing playback has stalled waiting for data. Approximated from
  /// `isPlaybackLikelyToKeepUp`, gated on having actually started and the play intent (so an
  /// initial connect / seek doesn't read as a stall). Mirrors the Android `stalled` signal, which
  /// the load control distinguishes natively.
  var isStalled: Bool {
    guard let item = avPlayer.currentItem else { return false }
    return currentTime > Self.stallGraceSeconds && playWhenReady && !item.isPlaybackLikelyToKeepUp
  }

  /**
   Controls the time pitch algorithm applied to each track loaded into the player.
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

  var reasonForWaitingToPlay: AVPlayer.WaitingReason? {
    avPlayer.reasonForWaitingToPlay
  }

  // MARK: - Getters from AVPlayer

  var currentTime: Double {
    let seconds = avPlayer.currentTime().seconds
    return seconds.isNaN ? 0 : seconds
  }

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

  var bufferedPosition: Double {
    avPlayer.currentItem?.loadedTimeRanges.last?.timeRangeValue.end.seconds ?? 0
  }

  var playerState: PlaybackState {
    state
  }

  // MARK: - Setters for AVPlayer

  var bufferDuration: Double = 0 {
    didSet {
      avPlayer.automaticallyWaitsToMinimizeStalling = bufferDuration == 0
      mediaLoader.bufferDuration = bufferDuration
    }
  }

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

  var rate: Float {
    get { coordinator.rate }
    set {
      coordinator.rate = newValue
      avPlayer.rate = newValue
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

    let errorHandler = PlaybackErrorHandler(retryHandler: retryManager)
    coordinator = PlaybackCoordinator(errorHandler: errorHandler, sleepTimerManager: SleepTimerManager())
    coordinator.effectHandler = self
    coordinator.callbacks = callbacks

    mediaLoader.delegate = self

    // Configure retry manager
    retryManager.shouldRetry = { [weak self] in
      self?.playWhenReady ?? false
    }
    retryManager.onRetry = { [weak self] startFromCurrentTime in
      self?.reload(startFromCurrentTime: startFromCurrentTime)
    }

    // Handle command center changes when MPNowPlayingSession is created/destroyed (iOS 16+)
    nowPlayingInfoController.onRemoteCommandCenterChanged = { [weak self] newCommandCenter in
      MainActor.assumeIsolated {
        self?.remoteCommandController.switchCommandCenter(newCommandCenter)
      }
    }

    setupAVPlayer()
  }

  // MARK: - Coordinator Forwarding Methods

  func getPlayback() -> Playback { coordinator.getPlayback() }
  func getRepeatMode() -> RepeatMode { coordinator.getRepeatMode() }
  func setRepeatMode(_ mode: RepeatMode) { coordinator.setRepeatMode(mode) }
  func handlePlayWhenReady(_ playWhenReady: Bool?, action: () throws -> Void) rethrows {
    try coordinator.handlePlayWhenReady(playWhenReady, action: action)
  }

  func transition(_ event: PlaybackEvent) { coordinator.transition(event) }

  // MARK: - Player Actions

  func load(_ track: Track, playWhenReady: Bool? = nil) {
    coordinator.load(track, playWhenReady: playWhenReady)
  }

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

  func play() { coordinator.play() }
  func pause() { coordinator.pause() }
  func togglePlayback() { coordinator.togglePlayback() }

  func handleInterruptionBegan() { coordinator.handleInterruptionBegan() }
  func handleInterruptionEnded(shouldResume: Bool) {
    coordinator.handleInterruptionEnded(shouldResume: shouldResume)
  }

  func stop() {
    coordinator.stop()
    if currentTrack?.live != true {
      seekTo(0)
    }
  }

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

  /// Re-establish a stalled stream when connectivity is restored.
  ///
  /// AVPlayer doesn't reliably surface a mid-stream connectivity loss on a live stream as an item
  /// failure — it sits in `.waitingToPlayAtSpecifiedRate` (a buffering stall) with no error, so the
  /// error-driven `RetryManager` never engages. Without this, the stream stays "reconnecting"
  /// indefinitely after the network returns. Mirrors Android, where ExoPlayer surfaces the drop as a
  /// retryable load error and re-prepares on restore.
  ///
  /// Guards: reload only while actually stalled (`.buffering`) with play intent — so a user-initiated
  /// pause, or a retry that already gave up (`.error`, past the max retry duration) isn't surprisingly
  /// resumed. Skipped when `RetryManager` is already parked waiting for the network: an error *did*
  /// schedule a retry that owns its own reload, so we'd otherwise load twice.
  func handleNetworkRestored() {
    guard playWhenReady,
          coordinator.state == .buffering,
          !retryManager.isWaitingForNetwork
    else { return }
    logger.info("Connectivity restored while stalled — reloading to reconnect")
    reload(startFromCurrentTime: true)
  }

  /// Jump to the live edge. No-op for non-live tracks. Live with a seekable
  /// window (HLS) seeks to the window end; live without one (non-seekable, e.g.
  /// ICY) has no window to seek within, so reconnect to rejoin live.
  func seekToLiveEdge() {
    guard currentTrack?.live == true, let item = avPlayer.currentItem else { return }
    if let range = item.seekableTimeRanges.last?.timeRangeValue, range.duration.seconds > 0 {
      avPlayer.seek(to: range.end, toleranceBefore: .zero, toleranceAfter: .zero)
    } else {
      reload(startFromCurrentTime: false)
    }
  }

  func seekTo(_ seconds: TimeInterval) {
    seekTo(seconds, completion: { _ in })
  }

  func seekTo(_ seconds: TimeInterval, completion: @escaping @MainActor (Bool) -> Void) {
    if state == .loading {
      loadSeekCoordinator.capture(position: seconds)
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
      completion(false)
    }
  }

  func seekBy(_ offset: TimeInterval) {
    let targetTime: TimeInterval
    if state == .loading {
      targetTime = (loadSeekCoordinator.pendingTime ?? 0) + offset
    } else if let currentItem = avPlayer.currentItem {
      targetTime = currentItem.currentTime().seconds + offset
    } else {
      return
    }
    seekTo(targetTime)
  }

  // MARK: - Remote Command Center

  func enableRemoteCommands(_ commands: [RemoteCommand]) {
    remoteCommandController.enable(commands: commands)
    // Apply current next/previous availability so re-/late-configured commands
    // start in the correct enabled state (not unconditionally enabled).
    remoteCommandController.setSkipAvailability(
      canNext: coordinator.canNext, canPrevious: coordinator.canPrevious,
    )
  }

  func clear() {
    coordinator.clear()
    nowPlayingInfoController.unlinkPlayer()
  }

  func destroy() {
    clear()
    remoteCommandController.disableAll()
  }

  // MARK: - Progress Updates

  func setProgressUpdateInterval(_ interval: TimeInterval?) {
    coordinator.setProgressUpdateInterval(interval)
  }

  func setPlaybackIntervalEnabled(_ enabled: Bool) {
    coordinator.setPlaybackIntervalEnabled(enabled)
  }

  // MARK: - AVPlayer Management

  func setTimePitchingAlgorithmForCurrentItem() {
    avPlayer.currentItem?.audioTimePitchAlgorithm = audioTimePitchAlgorithm
  }

  func startPlayback() {
    avPlayer.play()
    if rate != 1.0 {
      avPlayer.rate = rate
    }
  }

  func pausePlayback() {
    avPlayer.pause()
  }

  func clearCurrentAVItem() {
    stopObservingAVPlayerItem()
    mediaLoader.clearAsset()
    loadSeekCoordinator.reset()
    avPlayer.replaceCurrentItem(with: nil)
  }

  func startObservingAVPlayerItem(_ avItem: AVPlayerItem) {
    playerItemObserver.startObserving(item: avItem)
    playerItemNotificationObserver.startObserving(item: avItem)
  }

  func stopObservingAVPlayerItem() {
    playerItemObserver.stopObservingCurrentItem()
    playerItemNotificationObserver.stopObservingCurrentItem()
  }

  private func recreateAVPlayer() {
    coordinator.playbackError = nil
    playerTimeObserver.unregisterForBoundaryTimeEvents()
    playerObserver.stopObserving()
    stopObservingAVPlayerItem()
    clearCurrentAVItem()

    nowPlayingInfoController.unlinkPlayer()

    avPlayer = AVPlayer()
    setupAVPlayer()
  }

  private func setupAVPlayer() {
    avPlayer.allowsExternalPlayback = false

    playerObserver.avPlayer = avPlayer
    playerObserver.startObserving()

    playerTimeObserver.avPlayer = avPlayer
    playerTimeObserver.registerForBoundaryTimeEvents()

    nowPlayingInfoController.linkPlayer(avPlayer)

    if playWhenReady {
      startPlayback()
    } else {
      avPlayer.defaultRate = rate
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

  // MARK: - Observer Callbacks (map AVFoundation → coordinator)

  private func avPlayerDidChangeTimeControlStatus(_ status: AVPlayer.TimeControlStatus) {
    let mapped: PlayerTimeControlStatus
    switch status {
    case .paused: mapped = .paused
    case .waitingToPlayAtSpecifiedRate: mapped = .waitingToPlayAtSpecifiedRate
    case .playing: mapped = .playing
    @unknown default: return
    }
    coordinator.avPlayerDidChangeTimeControlStatus(mapped)
  }

  private func avPlayerStatusDidChange(_ status: AVPlayer.Status) {
    if status == .failed {
      coordinator.avPlayerStatusDidFail(error: avPlayer.currentItem?.error)
    }
  }

  private func avItemStatusDidChange(_ status: AVPlayerItem.Status, error: Error?) {
    let mapped: PlayerItemStatus
    switch status {
    case .unknown: mapped = .unknown
    case .readyToPlay: mapped = .readyToPlay
    case .failed: mapped = .failed
    @unknown default: return
    }
    coordinator.avItemStatusDidChange(mapped, error: error ?? avPlayer.currentItem?.error)
  }

  private func avItemDidUpdatePlaybackLikelyToKeepUp(_ playbackLikelyToKeepUp: Bool) {
    guard playbackLikelyToKeepUp else { return }
    guard avPlayer.currentItem != nil else { return }

    // Execute any pending seek that arrived after MediaLoader completed
    if loadSeekCoordinator.executeIfPending(on: avPlayer, delegate: self) {
      return
    }

    coordinator.avItemDidUpdatePlaybackLikelyToKeepUp(playbackLikelyToKeepUp)
  }

  func handleSeekCompleted(to seconds: Double, didFinish: Bool) {
    if loadSeekCoordinator.seekDidComplete(on: avPlayer, delegate: self), state == .loading {
      coordinator.handleSeekCompleted(to: seconds, didFinish: didFinish)
    }
    callbacks?.playerDidCompleteSeek(position: seconds, didFinish: didFinish)
  }
}

// MARK: - PlaybackEffectHandler

extension TrackPlayer: PlaybackEffectHandler {
  var hasLoadedAsset: Bool {
    mediaLoader.asset != nil
  }

  func clearCurrentItem() {
    clearCurrentAVItem()
  }

  func stopObservingCurrentItem() {
    stopObservingAVPlayerItem()
  }

  func loadTrack(src: String, track: Track) {
    mediaLoader.resolveAndLoad(src: src, track: track)
  }

  func reloadTrack(startFromCurrentTime: Bool) {
    reload(startFromCurrentTime: startFromCurrentTime)
  }

  func unloadTrack() {
    unloadAVPlayer()
  }

  func cancelMediaLoading() {
    mediaLoader.cancelAll()
  }

  func seekToStart() {
    seekTo(0)
  }

  func replayCurrentTrack() {
    seekTo(0) { [weak self] succeeded in
      if succeeded { self?.play() }
    }
  }

  func updateNowPlayingState(playWhenReady: Bool) {
    nowPlayingInfoController.setPlaybackState(playing: playWhenReady)
  }

  func loadNowPlayingMetadata(for track: Track) {
    nowPlayingUpdater.loadMetaValues(for: track)
  }

  func clearNowPlaying() {
    nowPlayingInfoController.clear()
  }

  func updateRemoteRepeatMode(_ mode: RepeatMode) {
    remoteCommandController.updateRepeatMode(mode)
  }

  func updateRemoteShuffleMode(_ enabled: Bool) {
    remoteCommandController.updateShuffleMode(enabled)
  }

  func updateSkipAvailability(canNext: Bool, canPrevious: Bool) {
    remoteCommandController.setSkipAvailability(canNext: canNext, canPrevious: canPrevious)
  }
}

// MARK: - SeekCompletionHandler

extension TrackPlayer: SeekCompletionHandler {}

// MARK: - TrackSelectionPlayer

extension TrackPlayer: TrackSelectionPlayer {}

// MARK: - Queue Methods (thin forwarders)

extension TrackPlayer {
  func replace(_ index: Int, _ track: Track) {
    coordinator.replace(index, track)
  }

  func setQueue(
    _ newTracks: [Track],
    initialIndex: Int = 0,
    startPositionMs: Double? = nil,
    playWhenReady: Bool? = nil,
    sourcePath: String? = nil,
  ) {
    coordinator.setQueue(
      newTracks,
      initialIndex: initialIndex,
      startPositionMs: startPositionMs,
      playWhenReady: playWhenReady,
      sourcePath: sourcePath,
    )
  }

  func add(_ tracks: [Track], initialIndex: Int? = nil, playWhenReady: Bool? = nil) {
    coordinator.add(tracks, initialIndex: initialIndex, playWhenReady: playWhenReady)
  }

  func add(_ tracks: [Track], at index: Int) throws {
    try coordinator.add(tracks, at: index)
  }

  func next() {
    coordinator.next()
  }

  func previous() {
    coordinator.previous()
  }

  func remove(_ index: Int) throws {
    try coordinator.remove(index)
  }

  func skipTo(_ index: Int, playWhenReady: Bool? = nil) throws {
    try coordinator.skipTo(index, playWhenReady: playWhenReady)
  }

  func move(fromIndex: Int, toIndex: Int) throws {
    try coordinator.move(fromIndex: fromIndex, toIndex: toIndex)
  }

  func removeUpcomingTracks() {
    coordinator.removeUpcomingTracks()
  }

  func replay() {
    coordinator.replay()
  }
}

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
    coordinator.errorHandler.handleError(error, context: .mediaLoad)
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
