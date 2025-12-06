import AVFoundation
import Foundation
import MediaPlayer
import NitroModules

public class HybridAudioBrowser: HybridAudioBrowserSpec {
  // MARK: - Private Properties

  private var player: TrackPlayer?
  private let networkMonitor = NetworkMonitor()

  // MARK: - Browser Properties

  public var path: String? {
    get { nil } // TODO: Implement browser
    set { } // TODO: Implement browser
  }

  public var tabs: [Track]? {
    get { nil } // TODO: Implement browser
    set { } // TODO: Implement browser
  }

  public var configuration: NativeBrowserConfiguration = NativeBrowserConfiguration(
    path: nil, request: nil, media: nil, artwork: nil, routes: nil,
    singleTrack: nil, androidControllerOfflineError: nil
  ) {
    didSet {
      // TODO: Update browser config
    }
  }

  // MARK: - Browser Callbacks

  public var onPathChanged: (String) -> Void = { _ in }
  public var onContentChanged: (ResolvedTrack?) -> Void = { _ in }
  public var onTabsChanged: ([Track]) -> Void = { _ in }
  public var onNavigationError: (NavigationErrorEvent) -> Void = { _ in }

  // MARK: - Player Callbacks

  public var onMetadataChapterReceived: (AudioMetadataReceivedEvent) -> Void = { _ in }
  public var onMetadataCommonReceived: (AudioCommonMetadataReceivedEvent) -> Void = { _ in }
  public var onMetadataTimedReceived: (AudioMetadataReceivedEvent) -> Void = { _ in }
  public var onPlaybackMetadata: (PlaybackMetadata) -> Void = { _ in }
  public var onPlaybackActiveTrackChanged: (PlaybackActiveTrackChangedEvent) -> Void = { _ in }
  public var onPlaybackError: (PlaybackErrorEvent) -> Void = { _ in }
  public var onPlaybackPlayWhenReadyChanged: (PlaybackPlayWhenReadyChangedEvent) -> Void = { _ in }
  public var onPlaybackPlayingState: (PlayingState) -> Void = { _ in }
  public var onPlaybackProgressUpdated: (PlaybackProgressUpdatedEvent) -> Void = { _ in }
  public var onPlaybackQueueEnded: (PlaybackQueueEndedEvent) -> Void = { _ in }
  public var onPlaybackQueueChanged: ([Track]) -> Void = { _ in }
  public var onPlaybackRepeatModeChanged: (RepeatModeChangedEvent) -> Void = { _ in }
  public var onSleepTimerChanged: (SleepTimer?) -> Void = { _ in }
  public var onPlaybackChanged: (Playback) -> Void = { _ in }

  // MARK: - Remote Callbacks

  public var onRemoteBookmark: () -> Void = {}
  public var onRemoteDislike: () -> Void = {}
  public var onRemoteJumpBackward: (RemoteJumpBackwardEvent) -> Void = { _ in }
  public var onRemoteJumpForward: (RemoteJumpForwardEvent) -> Void = { _ in }
  public var onRemoteLike: () -> Void = {}
  public var onRemoteNext: () -> Void = {}
  public var onRemotePause: () -> Void = {}
  public var onRemotePlay: () -> Void = {}
  public var onRemotePlayId: (RemotePlayIdEvent) -> Void = { _ in }
  public var onRemotePlaySearch: (RemotePlaySearchEvent) -> Void = { _ in }
  public var onRemotePrevious: () -> Void = {}
  public var onRemoteSeek: (RemoteSeekEvent) -> Void = { _ in }
  public var onRemoteSetRating: (RemoteSetRatingEvent) -> Void = { _ in }
  public var onRemoteSkip: (RemoteSkipEvent) -> Void = { _ in }
  public var onRemoteStop: () -> Void = {}

  // MARK: - Remote Handlers (optional overrides from JS)

  public var handleRemoteBookmark: (() -> Void)?
  public var handleRemoteDislike: (() -> Void)?
  public var handleRemoteJumpBackward: ((RemoteJumpBackwardEvent) -> Void)?
  public var handleRemoteJumpForward: ((RemoteJumpForwardEvent) -> Void)?
  public var handleRemoteLike: (() -> Void)?
  public var handleRemoteNext: (() -> Void)?
  public var handleRemotePause: (() -> Void)?
  public var handleRemotePlay: (() -> Void)?
  public var handleRemotePlayId: ((RemotePlayIdEvent) -> Void)?
  public var handleRemotePlaySearch: ((RemotePlaySearchEvent) -> Void)?
  public var handleRemotePrevious: (() -> Void)?
  public var handleRemoteSeek: ((RemoteSeekEvent) -> Void)?
  public var handleRemoteSkip: (() -> Void)?
  public var handleRemoteStop: (() -> Void)?

  // MARK: - Other Callbacks

  public var onOptionsChanged: (Options) -> Void = { _ in }
  public var onFavoriteChanged: (FavoriteChangedEvent) -> Void = { _ in }
  public var onNowPlayingChanged: (NowPlayingMetadata) -> Void = { _ in }
  public var onOnlineChanged: (Bool) -> Void = { _ in } {
    didSet {
      networkMonitor.onChanged = { [weak self] isOnline in
        self?.onOnlineChanged(isOnline)
      }
      // Immediately notify current state
      onOnlineChanged(networkMonitor.isOnline)
    }
  }
  public var onEqualizerChanged: (EqualizerSettings) -> Void = { _ in }
  public var onBatteryWarningPendingChanged: (BatteryWarningPendingChangedEvent) -> Void = { _ in }
  public var onBatteryOptimizationStatusChanged: (BatteryOptimizationStatusChangedEvent) -> Void = { _ in }

  // MARK: - Browser Methods

  public func navigatePath(path: String) throws {
    // TODO: Implement browser navigation
  }

  public func navigateTrack(track: Track) throws {
    // If track has src, load it
    if track.src != nil {
      try load(track: track)
    }
    // TODO: Handle browsable tracks (navigate to url)
  }

  public func onSearch(query: String) throws -> Promise<[Track]> {
    // TODO: Implement search
    return Promise.resolved(withResult: [])
  }

  public func getContent() throws -> ResolvedTrack? {
    // TODO: Implement browser
    return nil
  }

  public func getNavigationError() throws -> NavigationError? {
    // TODO: Implement browser
    return nil
  }

  public func notifyContentChanged(path: String) throws {
    // TODO: Implement browser
  }

  public func setFavorites(favorites: [String]) throws {
    // TODO: Implement favorites
  }

  // MARK: - Player Setup

  public func setupPlayer(options: PartialSetupPlayerOptions) throws -> Promise<Void> {
    return Promise.async {
      // Configure audio session
      let session = AVAudioSession.sharedInstance()
      try session.setCategory(.playback, mode: .default)
      try session.setActive(true)

      // Create player with self as callbacks delegate
      self.player = TrackPlayer(callbacks: self)

      // Configure sleep timer callback
      self.player?.sleepTimerManager.onChanged = { [weak self] state in
        self?.onSleepTimerChanged(state)
      }

      // TODO: Apply options
    }
  }

  public func updateOptions(options: NativeUpdateOptions) throws {
    // TODO: Implement
  }

  public func getOptions() throws -> UpdateOptions {
    // TODO: Return actual options
    return UpdateOptions(
      android: nil,
      ios: nil,
      forwardJumpInterval: nil,
      backwardJumpInterval: nil,
      progressUpdateEventInterval: nil,
      capabilities: nil
    )
  }

  // MARK: - Playback Control

  public func load(track: Track) throws {
    guard let player = player else {
      throw NSError(domain: "AudioBrowser", code: 1, userInfo: [NSLocalizedDescriptionKey: "Player not initialized"])
    }
    player.load(track)
  }

  public func reset() throws {
    player?.clear()
  }

  public func play() throws {
    player?.play()
  }

  public func pause() throws {
    player?.pause()
  }

  public func togglePlayback() throws {
    player?.togglePlayback()
  }

  public func stop() throws {
    player?.stop()
  }

  public func setPlayWhenReady(playWhenReady: Bool) throws {
    player?.playWhenReady = playWhenReady
  }

  public func getPlayWhenReady() throws -> Bool {
    return player?.playWhenReady ?? false
  }

  public func seekTo(position: Double) throws {
    player?.seekTo(position)
  }

  public func seekBy(offset: Double) throws {
    player?.seekBy(offset)
  }

  public func setVolume(level: Double) throws {
    player?.volume = Float(level)
  }

  public func getVolume() throws -> Double {
    return Double(player?.volume ?? 1.0)
  }

  public func setRate(rate: Double) throws {
    player?.rate = Float(rate)
  }

  public func getRate() throws -> Double {
    return Double(player?.rate ?? 1.0)
  }

  public func getProgress() throws -> Progress {
    return Progress(
      position: player?.currentTime ?? 0,
      duration: player?.duration ?? 0,
      buffered: player?.bufferedPosition ?? 0
    )
  }

  public func getPlayback() throws -> Playback {
    return player?.getPlayback() ?? Playback(state: .none, error: nil)
  }

  public func getPlayingState() throws -> PlayingState {
    return player?.playingStateManager.toPlayingState() ?? PlayingState(playing: false, buffering: false)
  }

  public func getRepeatMode() throws -> RepeatMode {
    return player?.repeatMode ?? .off
  }

  public func setRepeatMode(mode: RepeatMode) throws {
    player?.repeatMode = mode
  }

  public func getPlaybackError() throws -> PlaybackError? {
    return player?.playbackError?.toNitroError()
  }

  public func retry() throws {
    player?.reload(startFromCurrentTime: true)
  }

  // MARK: - Sleep Timer

  public func getSleepTimer() throws -> SleepTimer {
    if let state = player?.sleepTimerManager.get() {
      return state
    }
    return .first(NullType.null)
  }

  public func setSleepTimer(seconds: Double) throws {
    player?.sleepTimerManager.set(seconds: seconds)
  }

  public func setSleepTimerToEndOfTrack() throws {
    player?.sleepTimerManager.setToEndOfTrack()
  }

  public func clearSleepTimer() throws -> Bool {
    return player?.sleepTimerManager.clear() ?? false
  }

  // MARK: - Queue Management

  public func add(tracks: [Track], insertBeforeIndex: Double?) throws {
    guard let player = player else { return }
    if let index = insertBeforeIndex {
      try player.add(tracks, at: Int(index))
    } else {
      player.add(tracks)
    }
  }

  public func move(fromIndex: Double, toIndex: Double) throws {
    try player?.move(fromIndex: Int(fromIndex), toIndex: Int(toIndex))
  }

  public func remove(indexes: [Double]) throws {
    guard let player = player else { return }
    // Remove in reverse order to maintain index validity
    for index in indexes.sorted().reversed() {
      try player.remove(Int(index))
    }
  }

  public func removeUpcomingTracks() throws {
    player?.removeUpcomingTracks()
  }

  public func skip(index: Double, initialPosition: Double?) throws {
    try player?.skipTo(Int(index))
    if let position = initialPosition {
      player?.seekTo(position)
    }
  }

  public func skipToNext(initialPosition: Double?) throws {
    player?.next()
    if let position = initialPosition {
      player?.seekTo(position)
    }
  }

  public func skipToPrevious(initialPosition: Double?) throws {
    player?.previous()
    if let position = initialPosition {
      player?.seekTo(position)
    }
  }

  public func setActiveTrackFavorited(favorited: Bool) throws {
    // TODO: Implement favorites
  }

  public func toggleActiveTrackFavorited() throws {
    // TODO: Implement favorites
  }

  public func setQueue(tracks: [Track], startIndex: Double?, startPositionMs: Double?) throws {
    guard let player = player else { return }
    player.clear()
    player.add(tracks)
    if let index = startIndex, index >= 0 {
      try player.skipTo(Int(index))
    }
    if let position = startPositionMs {
      player.seekTo(position / 1000.0)
    }
  }

  public func getQueue() throws -> [Track] {
    return player?.tracks ?? []
  }

  public func getTrack(index: Double) throws -> Track? {
    guard let tracks = player?.tracks else { return nil }
    let i = Int(index)
    guard i >= 0, i < tracks.count else { return nil }
    return tracks[i]
  }

  public func getActiveTrackIndex() throws -> Double? {
    guard let index = player?.currentIndex, index >= 0 else { return nil }
    return Double(index)
  }

  public func getActiveTrack() throws -> Track? {
    return player?.currentTrack
  }

  // MARK: - Now Playing

  public func updateNowPlaying(update: NowPlayingUpdate?) throws {
    // TODO: Implement now playing override
  }

  public func getNowPlaying() throws -> NowPlayingMetadata? {
    // TODO: Implement
    return nil
  }

  // MARK: - Network

  public func getOnline() throws -> Bool {
    return networkMonitor.getOnline()
  }

  // MARK: - Equalizer

  public func getEqualizerSettings() throws -> EqualizerSettings? {
    // iOS has limited equalizer support
    return nil
  }

  public func setEqualizerEnabled(enabled: Bool) throws {
    // iOS has limited equalizer support
  }

  public func setEqualizerPreset(preset: String) throws {
    // iOS has limited equalizer support
  }

  public func setEqualizerLevels(levels: [Double]) throws {
    // iOS has limited equalizer support
  }

  // MARK: - Battery (Android-only, stub implementations)

  public func getBatteryWarningPending() throws -> Bool {
    return false
  }

  public func getBatteryOptimizationStatus() throws -> BatteryOptimizationStatus {
    // iOS doesn't have battery optimization restrictions like Android
    return .unrestricted
  }

  public func dismissBatteryWarning() throws {
    // No-op on iOS
  }

  public func openBatterySettings() throws {
    // No-op on iOS
  }
}

// MARK: - TrackPlayerCallbacks

extension HybridAudioBrowser: TrackPlayerCallbacks {
  public func playerDidChangePlayback(_ playback: Playback) {
    onPlaybackChanged(playback)
  }

  public func playerDidChangeActiveTrack(_ event: PlaybackActiveTrackChangedEvent) {
    onPlaybackActiveTrackChanged(event)
  }

  public func playerDidUpdateProgress(_ event: PlaybackProgressUpdatedEvent) {
    onPlaybackProgressUpdated(event)
  }

  public func playerDidChangePlayWhenReady(_ playWhenReady: Bool) {
    onPlaybackPlayWhenReadyChanged(PlaybackPlayWhenReadyChangedEvent(playWhenReady: playWhenReady))
  }

  public func playerDidChangePlayingState(_ state: PlayingState) {
    onPlaybackPlayingState(state)
  }

  public func playerDidEndQueue(_ event: PlaybackQueueEndedEvent) {
    onPlaybackQueueEnded(event)
  }

  public func playerDidChangeRepeatMode(_ event: RepeatModeChangedEvent) {
    onPlaybackRepeatModeChanged(event)
  }

  public func playerDidError(_ event: PlaybackErrorEvent) {
    onPlaybackError(event)
  }

  public func playerDidReceiveCommonMetadata(_ metadata: [AVMetadataItem]) {
    // TODO: Convert to AudioCommonMetadataReceivedEvent
  }

  public func playerDidReceiveTimedMetadata(_ metadata: [AVTimedMetadataGroup]) {
    // TODO: Convert to AudioMetadataReceivedEvent
  }

  public func playerDidReceiveChapterMetadata(_ metadata: [AVTimedMetadataGroup]) {
    // TODO: Convert to AudioMetadataReceivedEvent
  }

  public func playerDidCompleteSeek(position: Double, didFinish: Bool) {
    // Not exposed to JS currently
  }

  public func playerDidUpdateDuration(_ duration: Double) {
    // Not exposed to JS currently
  }

  public func remotePlay() {
    if let handler = handleRemotePlay {
      handler()
    } else {
      onRemotePlay()
    }
  }

  public func remotePause() {
    if let handler = handleRemotePause {
      handler()
    } else {
      onRemotePause()
    }
  }

  public func remoteStop() {
    if let handler = handleRemoteStop {
      handler()
    } else {
      onRemoteStop()
    }
  }

  public func remotePlayPause() {
    // Toggle based on current state
    if player?.playWhenReady == true {
      remotePause()
    } else {
      remotePlay()
    }
  }

  public func remoteNext() {
    if let handler = handleRemoteNext {
      handler()
    } else {
      onRemoteNext()
    }
  }

  public func remotePrevious() {
    if let handler = handleRemotePrevious {
      handler()
    } else {
      onRemotePrevious()
    }
  }

  public func remoteJumpForward(interval: Double) {
    let event = RemoteJumpForwardEvent(interval: interval)
    if let handler = handleRemoteJumpForward {
      handler(event)
    } else {
      onRemoteJumpForward(event)
    }
  }

  public func remoteJumpBackward(interval: Double) {
    let event = RemoteJumpBackwardEvent(interval: interval)
    if let handler = handleRemoteJumpBackward {
      handler(event)
    } else {
      onRemoteJumpBackward(event)
    }
  }

  public func remoteSeek(position: Double) {
    let event = RemoteSeekEvent(position: position)
    if let handler = handleRemoteSeek {
      handler(event)
    } else {
      onRemoteSeek(event)
    }
  }

  public func remoteChangePlaybackPosition(position: Double) {
    remoteSeek(position: position)
  }

  public func remoteSetRating(rating: Any) {
    // TODO: Convert rating to RemoteSetRatingEvent
  }

  public func remotePlayId(id: String, index: Int?) {
    let event = RemotePlayIdEvent(id: id, index: index.map { Double($0) })
    if let handler = handleRemotePlayId {
      handler(event)
    } else {
      onRemotePlayId(event)
    }
  }

  public func remotePlaySearch(query: String) {
    let event = RemotePlaySearchEvent(query: query)
    if let handler = handleRemotePlaySearch {
      handler(event)
    } else {
      onRemotePlaySearch(event)
    }
  }

  public func remoteLike() {
    if let handler = handleRemoteLike {
      handler()
    } else {
      onRemoteLike()
    }
  }

  public func remoteDislike() {
    if let handler = handleRemoteDislike {
      handler()
    } else {
      onRemoteDislike()
    }
  }

  public func remoteBookmark() {
    if let handler = handleRemoteBookmark {
      handler()
    } else {
      onRemoteBookmark()
    }
  }

  func playerDidChangeOptions(_ options: PlayerUpdateOptions) {
    // TODO: Convert to Options type
  }
}

// MARK: - Autolinking Alias

/// Alias for Nitro autolinking - expects class named "AudioBrowser"
public typealias AudioBrowser = HybridAudioBrowser
