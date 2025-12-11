import AVFoundation
import Foundation
import MediaPlayer
import NitroModules
import os.log

public class HybridAudioBrowser: HybridAudioBrowserSpec {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "AudioBrowser")

  // MARK: - Shared Instance for CarPlay

  /// Shared instance for CarPlay access. Set when HybridAudioBrowser is created.
  private(set) weak static var shared: HybridAudioBrowser?

  // MARK: - Private Properties

  private var player: TrackPlayer?
  private let networkMonitor = NetworkMonitor()
  let browserManager = BrowserManager()
  private var nowPlayingOverride: NowPlayingUpdate?
  private let playerOptions = PlayerUpdateOptions()
  private var lastNavigationError: NavigationError? {
    didSet {
      // Skip if both nil (no real change)
      guard oldValue != nil || lastNavigationError != nil else { return }
      onNavigationError(NavigationErrorEvent(error: lastNavigationError))
    }
  }

  // MARK: - Internal Callbacks (for CarPlay/external controllers)

  /// Called when notifyContentChanged is invoked, allowing CarPlay to refresh its templates.
  /// Set by CarPlayController during setup.
  var onExternalContentChanged: ((String) -> Void)?

  // MARK: - Thread Safety

  /// Executes a closure on the main thread synchronously, returning its result.
  /// If already on main thread, executes directly.
  private func onMainThread<T>(_ work: () -> T) -> T {
    if Thread.isMainThread {
      work()
    } else {
      DispatchQueue.main.sync { work() }
    }
  }

  /// Executes a throwing closure on the main thread synchronously.
  private func onMainThread<T>(_ work: () throws -> T) throws -> T {
    if Thread.isMainThread {
      try work()
    } else {
      try DispatchQueue.main.sync { try work() }
    }
  }

  // MARK: - Browser Properties

  public var path: String? {
    get { browserManager.getPath() }
    set {
      guard let newPath = newValue else { return }
      Task {
        do {
          try await browserManager.navigate(newPath)
        } catch {
          handleNavigationError(error, path: newPath)
        }
      }
    }
  }

  public var tabs: [Track]? {
    get { browserManager.getTabs() }
    set { /* tabs are managed internally by browserManager */ }
  }

  public var configuration: NativeBrowserConfiguration = .init(
    path: nil, request: nil, media: nil, artwork: nil, routes: nil,
    singleTrack: nil, androidControllerOfflineError: nil, carPlayUpNextButton: nil,
    carPlayNowPlayingButtons: nil, carPlayNowPlayingRates: nil,
  ) {
    didSet {
      browserManager.config = BrowserConfig(from: configuration)
      // Query tabs and navigate to initial path after config is set (matches Kotlin behavior)
      Task {
        let tabs = try? await browserManager.queryTabs()
        // Navigate to configured path, first tab, or "/"
        let initialPath = configuration.path ?? tabs?.first?.url ?? "/"
        // Clear error before navigation (matches Kotlin clearNavigationError())
        await MainActor.run { lastNavigationError = nil }
        do {
          try await browserManager.navigate(initialPath)
        } catch {
          handleNavigationError(error, path: initialPath)
        }
      }
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
  public var onPlaybackShuffleModeChanged: (Bool) -> Void = { _ in }
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

  // MARK: - Initialization

  override public init() {
    super.init()
    HybridAudioBrowser.shared = self
    setupBrowserCallbacks()
  }

  /// Returns the TrackPlayer instance, if setup has been called.
  func getPlayer() -> TrackPlayer? {
    player
  }

  private func setupBrowserCallbacks() {
    browserManager.onPathChanged = { [weak self] path in
      self?.onPathChanged(path)
    }
    browserManager.onContentChanged = { [weak self] content in
      self?.onContentChanged(content)
    }
    browserManager.onTabsChanged = { [weak self] tabs in
      self?.onTabsChanged(tabs)
    }

    // Configure artwork URL resolver for transforming artwork URLs during browsing
    browserManager.artworkUrlResolver = { [weak browserManager] track, artworkConfig in
      guard let browserManager else { return nil }
      return await browserManager.resolveArtworkUrl(track: track, perRouteConfig: artworkConfig)
    }
  }

  private func handleNavigationError(_ error: Error, path _: String) {
    let navError = if let browserError = error as? BrowserError {
      switch browserError {
      case .contentNotFound:
        NavigationError(code: .contentNotFound, message: browserError.localizedDescription, statusCode: nil)
      case let .httpError(code, _):
        NavigationError(code: .httpError, message: browserError.localizedDescription, statusCode: Double(code))
      case .networkError:
        NavigationError(code: .networkError, message: browserError.localizedDescription, statusCode: nil)
      case .invalidConfiguration:
        NavigationError(code: .unknownError, message: browserError.localizedDescription, statusCode: nil)
      }
    } else {
      NavigationError(code: .unknownError, message: error.localizedDescription, statusCode: nil)
    }

    lastNavigationError = navError
  }

  // MARK: - Browser Methods

  public func navigatePath(path: String) throws {
    // Clear error synchronously before starting navigation (didSet notifies JS)
    lastNavigationError = nil
    Task {
      do {
        try await browserManager.navigate(path)
      } catch {
        handleNavigationError(error, path: path)
      }
    }
  }

  public func navigateTrack(track: Track) throws {
    let url = track.url

    // Check if this is a contextual URL (playable-only track with queue context)
    if let url, BrowserPathHelper.isContextual(url) {
      Task {
        do {
          // Expand the queue from the contextual URL
          if let expanded = try await browserManager.expandQueueFromContextualUrl(url) {
            let (tracks, startIndex) = expanded

            // Replace queue and start at the selected track (auto-play)
            await MainActor.run {
              player?.setQueue(tracks, initialIndex: startIndex, playWhenReady: true)
            }
          } else {
            // Fallback: just load the single track (auto-play)
            await MainActor.run {
              player?.load(track, playWhenReady: true)
            }
          }
        } catch {
          logger.error("Error expanding queue: \(error.localizedDescription)")
          // Fallback to single track (auto-play) - playback errors reported via TrackPlayer callbacks
          await MainActor.run {
            player?.load(track, playWhenReady: true)
          }
        }
      }
    }
    // If track has src, it's playable - load and auto-play
    else if track.src != nil {
      onMainThread {
        player?.load(track, playWhenReady: true)
      }
    }
    // If track has url, it's browsable - navigate to it
    else if let url {
      // Clear error synchronously before starting navigation (didSet notifies JS)
      lastNavigationError = nil
      Task {
        do {
          try await browserManager.navigate(url)
        } catch {
          handleNavigationError(error, path: url)
        }
      }
    }
  }

  public func onSearch(query: String) throws -> Promise<[Track]> {
    Promise.async { [weak self] in
      guard let self else { return [] }
      let resolved = try await browserManager.search(query)
      return resolved.children ?? []
    }
  }

  public func getContent() throws -> ResolvedTrack? {
    browserManager.getContent()
  }

  public func getNavigationError() throws -> NavigationError? {
    lastNavigationError
  }

  public func notifyContentChanged(path: String) throws {
    browserManager.invalidateContentCache(path)

    // Notify external controllers (CarPlay) that content changed
    onExternalContentChanged?(path)

    // Re-resolve the path if it's the current browser path
    if browserManager.getPath() == path {
      Task {
        do {
          try await browserManager.navigate(path)
        } catch {
          handleNavigationError(error, path: path)
        }
      }
    }
  }

  public func setFavorites(favorites: [String]) throws {
    browserManager.setFavorites(favorites)
  }

  // MARK: - Player Setup

  public func setupPlayer(options _: PartialSetupPlayerOptions) throws -> Promise<Void> {
    Promise.async {
      // Configure audio session
      let session = AVAudioSession.sharedInstance()
      try session.setCategory(.playback, mode: .default)
      try session.setActive(true)

      // Create player with self as callbacks delegate
      self.player = TrackPlayer(callbacks: self)

      // Configure media URL resolver
      self.player?.mediaUrlResolver = { [weak self] src in
        guard let self else {
          return MediaResolvedUrl(url: src, headers: nil, userAgent: nil)
        }
        return await browserManager.resolveMediaUrl(src)
      }

      // Configure sleep timer callback
      self.player?.sleepTimerManager.onChanged = { [weak self] state in
        self?.onSleepTimerChanged(state)
      }

      // Apply default remote commands (play, pause, next, previous, seekTo)
      await MainActor.run {
        self.applyRemoteCommands()
      }
    }
  }

  public func updateOptions(options: NativeUpdateOptions) throws {
    try onMainThread {
      // Update stored options
      playerOptions.update(from: options)

      // Apply remote commands to player
      applyRemoteCommands()
    }
  }

  /// Converts capabilities to remote commands and applies them to the player
  private func applyRemoteCommands() {
    guard let player else { return }

    let remoteCommands = playerOptions.capabilities.map { capability in
      capability.mapToPlayerCommand(
        forwardJumpInterval: NSNumber(value: playerOptions.forwardJumpInterval),
        backwardJumpInterval: NSNumber(value: playerOptions.backwardJumpInterval),
        likeOptions: playerOptions.likeOptions,
        dislikeOptions: playerOptions.dislikeOptions,
        bookmarkOptions: playerOptions.bookmarkOptions,
      )
    }

    player.remoteCommands = remoteCommands
  }

  public func getOptions() throws -> UpdateOptions {
    playerOptions.toUpdateOptions()
  }

  // MARK: - Playback Control

  public func load(track: Track) throws {
    try onMainThread {
      guard let player else {
        throw NSError(domain: "AudioBrowser", code: 1, userInfo: [NSLocalizedDescriptionKey: "Player not initialized"])
      }
      player.load(track)
    }
  }

  public func reset() throws {
    onMainThread { player?.clear() }
  }

  public func play() throws {
    onMainThread { player?.play() }
  }

  public func pause() throws {
    onMainThread { player?.pause() }
  }

  public func togglePlayback() throws {
    onMainThread { player?.togglePlayback() }
  }

  public func stop() throws {
    onMainThread { player?.stop() }
  }

  public func setPlayWhenReady(playWhenReady: Bool) throws {
    onMainThread { player?.playWhenReady = playWhenReady }
  }

  public func getPlayWhenReady() throws -> Bool {
    onMainThread { player?.playWhenReady ?? false }
  }

  public func seekTo(position: Double) throws {
    onMainThread { player?.seekTo(position) }
  }

  public func seekBy(offset: Double) throws {
    onMainThread { player?.seekBy(offset) }
  }

  public func setVolume(level: Double) throws {
    onMainThread { player?.volume = Float(level) }
  }

  public func getVolume() throws -> Double {
    onMainThread { Double(player?.volume ?? 1.0) }
  }

  public func setRate(rate: Double) throws {
    onMainThread { player?.rate = Float(rate) }
  }

  public func getRate() throws -> Double {
    onMainThread { Double(player?.rate ?? 1.0) }
  }

  public func getProgress() throws -> Progress {
    onMainThread {
      Progress(
        position: player?.currentTime ?? 0,
        duration: player?.duration ?? 0,
        buffered: player?.bufferedPosition ?? 0,
      )
    }
  }

  public func getPlayback() throws -> Playback {
    onMainThread { player?.getPlayback() ?? Playback(state: .none, error: nil) }
  }

  public func getPlayingState() throws -> PlayingState {
    onMainThread {
      player?.playingStateManager.toPlayingState() ?? PlayingState(playing: false, buffering: false)
    }
  }

  public func getRepeatMode() throws -> RepeatMode {
    onMainThread { player?.repeatMode ?? .off }
  }

  public func setRepeatMode(mode: RepeatMode) throws {
    onMainThread { player?.repeatMode = mode }
  }

  public func getShuffleEnabled() throws -> Bool {
    onMainThread { player?.shuffleEnabled ?? false }
  }

  public func setShuffleEnabled(enabled: Bool) throws {
    onMainThread { player?.shuffleEnabled = enabled }
  }

  public func getPlaybackError() throws -> PlaybackError? {
    onMainThread { player?.playbackError?.toNitroError() }
  }

  public func retry() throws {
    onMainThread { player?.reload(startFromCurrentTime: true) }
  }

  // MARK: - Sleep Timer

  public func getSleepTimer() throws -> SleepTimer {
    onMainThread {
      if let state = player?.sleepTimerManager.get() {
        return state
      }
      return .first(NullType.null)
    }
  }

  public func setSleepTimer(seconds: Double) throws {
    onMainThread {
      player?.sleepTimerManager.set(seconds: seconds)
    }
  }

  public func setSleepTimerToEndOfTrack() throws {
    onMainThread {
      player?.sleepTimerManager.setToEndOfTrack()
    }
  }

  public func clearSleepTimer() throws -> Bool {
    onMainThread {
      player?.sleepTimerManager.clear() ?? false
    }
  }

  // MARK: - Queue Management

  public func add(tracks: [Track], insertBeforeIndex: Double?) throws {
    try onMainThread {
      guard let player else { return }
      if let index = insertBeforeIndex {
        try player.add(tracks, at: Int(index))
      } else {
        player.add(tracks)
      }
    }
  }

  public func move(fromIndex: Double, toIndex: Double) throws {
    try onMainThread {
      try player?.move(fromIndex: Int(fromIndex), toIndex: Int(toIndex))
    }
  }

  public func remove(indexes: [Double]) throws {
    try onMainThread {
      guard let player else { return }
      // Remove in reverse order to maintain index validity
      for index in indexes.sorted().reversed() {
        try player.remove(Int(index))
      }
    }
  }

  public func removeUpcomingTracks() throws {
    onMainThread { player?.removeUpcomingTracks() }
  }

  public func skip(index: Double, initialPosition: Double?) throws {
    try onMainThread {
      try player?.skipTo(Int(index))
      if let position = initialPosition {
        player?.seekTo(position)
      }
    }
  }

  public func skipToNext(initialPosition: Double?) throws {
    onMainThread {
      player?.next()
      if let position = initialPosition {
        player?.seekTo(position)
      }
    }
  }

  public func skipToPrevious(initialPosition: Double?) throws {
    onMainThread {
      player?.previous()
      if let position = initialPosition {
        player?.seekTo(position)
      }
    }
  }

  public func setActiveTrackFavorited(favorited: Bool) throws {
    onMainThread {
      guard let track = player?.currentTrack, let src = track.src else { return }
      guard let index = player?.currentIndex, index >= 0 else { return }
      browserManager.updateFavorite(id: src, favorited: favorited)
      // Create updated track with new favorited state
      let updatedTrack = Track(
        url: track.url,
        src: track.src,
        artwork: track.artwork,
        artworkSource: track.artworkSource,
        title: track.title,
        subtitle: track.subtitle,
        artist: track.artist,
        album: track.album,
        description: track.description,
        genre: track.genre,
        duration: track.duration,
        style: track.style,
        childrenStyle: track.childrenStyle,
        favorited: favorited,
        groupTitle: track.groupTitle,
        live: track.live,
      )
      onFavoriteChanged(FavoriteChangedEvent(track: updatedTrack, favorited: favorited))
      // Fire active track changed so useActiveTrack() hook updates UI
      let position = player?.currentTime ?? 0
      onPlaybackActiveTrackChanged(PlaybackActiveTrackChangedEvent(
        lastIndex: Double(index),
        lastTrack: track,
        lastPosition: position,
        index: Double(index),
        track: updatedTrack,
      ))
    }
  }

  public func toggleActiveTrackFavorited() throws {
    onMainThread {
      guard let track = player?.currentTrack, let src = track.src else { return }
      // Check current favorited state from cache
      let currentTrack = browserManager.getCachedTrack(src)
      let isFavorited = currentTrack?.favorited ?? track.favorited ?? false
      try? setActiveTrackFavorited(favorited: !isFavorited)
    }
  }

  public func setQueue(tracks: [Track], startIndex: Double?, startPositionMs: Double?) throws {
    try onMainThread {
      guard let player else { return }
      player.clear()
      player.add(tracks)
      if let index = startIndex, index >= 0 {
        try player.skipTo(Int(index))
      }
      if let position = startPositionMs {
        player.seekTo(position / 1000.0)
      }
    }
  }

  public func getQueue() throws -> [Track] {
    onMainThread { player?.tracks ?? [] }
  }

  public func getTrack(index: Double) throws -> Track? {
    onMainThread {
      guard let tracks = player?.tracks else { return nil }
      let i = Int(index)
      guard i >= 0, i < tracks.count else { return nil }
      return tracks[i]
    }
  }

  public func getActiveTrackIndex() throws -> Double? {
    onMainThread {
      guard let index = player?.currentIndex, index >= 0 else { return nil }
      return Double(index)
    }
  }

  public func getActiveTrack() throws -> Track? {
    onMainThread { player?.currentTrack }
  }

  // MARK: - Now Playing

  public func updateNowPlaying(update: NowPlayingUpdate?) throws {
    onMainThread {
      nowPlayingOverride = update
      applyNowPlayingMetadata()
    }
  }

  public func getNowPlaying() throws -> NowPlayingMetadata? {
    onMainThread {
      guard let track = player?.currentTrack else { return nil }
      let override = nowPlayingOverride
      return NowPlayingMetadata(
        elapsedTime: player?.currentTime,
        title: override?.title ?? track.title,
        album: track.album,
        artist: override?.artist ?? track.artist,
        duration: track.duration,
        artwork: track.artwork,
        description: track.description,
        mediaId: track.src ?? track.url,
        genre: track.genre,
        rating: nil,
      )
    }
  }

  /// Applies the current now playing metadata (with override if set) to NowPlayingInfoCenter and notifies JS.
  private func applyNowPlayingMetadata() {
    guard let track = player?.currentTrack else { return }
    let override = nowPlayingOverride

    let nowPlaying = NowPlayingMetadata(
      elapsedTime: player?.currentTime,
      title: override?.title ?? track.title,
      album: track.album,
      artist: override?.artist ?? track.artist,
      duration: track.duration,
      artwork: track.artwork,
      description: track.description,
      mediaId: track.src ?? track.url,
      genre: track.genre,
      rating: nil,
    )

    // Update NowPlayingInfoCenter with override values
    if let override {
      player?.nowPlayingInfoController.set(keyValue: MediaItemProperty.title(override.title ?? track.title))
      player?.nowPlayingInfoController.set(keyValue: MediaItemProperty.artist(override.artist ?? track.artist))
    }

    onNowPlayingChanged(nowPlaying)
  }

  // MARK: - Network

  public func getOnline() throws -> Bool {
    networkMonitor.getOnline()
  }

  // MARK: - Equalizer (unsupported on iOS)

  public func getEqualizerSettings() throws -> EqualizerSettings? {
    // No-op: equalizer unsupported on iOS
    nil
  }

  public func setEqualizerEnabled(enabled _: Bool) throws {
    // No-op: equalizer unsupported on iOS
  }

  public func setEqualizerPreset(preset _: String) throws {
    // No-op: equalizer unsupported on iOS
  }

  public func setEqualizerLevels(levels _: [Double]) throws {
    // No-op: equalizer unsupported on iOS
  }

  // MARK: - Battery (Android-only, stub implementations)

  public func getBatteryWarningPending() throws -> Bool {
    false
  }

  public func getBatteryOptimizationStatus() throws -> BatteryOptimizationStatus {
    // iOS doesn't have battery optimization restrictions like Android
    .unrestricted
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
    // Clear now playing override when track changes (matches Kotlin behavior)
    nowPlayingOverride = nil
    onPlaybackActiveTrackChanged(event)
    // Also notify now playing changed when track changes
    applyNowPlayingMetadata()
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

  public func playerDidChangeQueue(_ tracks: [Track]) {
    onPlaybackQueueChanged(tracks)
  }

  public func playerDidChangeRepeatMode(_ event: RepeatModeChangedEvent) {
    onPlaybackRepeatModeChanged(event)
  }

  public func playerDidChangeShuffleEnabled(_ enabled: Bool) {
    onPlaybackShuffleModeChanged(enabled)
  }

  public func playerDidError(_ event: PlaybackErrorEvent) {
    onPlaybackError(event)
  }

  public func playerDidReceiveCommonMetadata(_: [AVMetadataItem]) {
    // TODO: Convert to AudioCommonMetadataReceivedEvent
  }

  public func playerDidReceiveTimedMetadata(_: [AVTimedMetadataGroup]) {
    // TODO: Convert to AudioMetadataReceivedEvent
  }

  public func playerDidReceiveChapterMetadata(_: [AVTimedMetadataGroup]) {
    // TODO: Convert to AudioMetadataReceivedEvent
  }

  public func playerDidCompleteSeek(position _: Double, didFinish _: Bool) {
    // Not exposed to JS currently
  }

  public func playerDidUpdateDuration(_: Double) {
    // Not exposed to JS currently
  }

  public func remotePlay() {
    if let handler = handleRemotePlay {
      handler()
    } else {
      // Default behavior: play the player
      onMainThread { player?.play() }
    }
    onRemotePlay()
  }

  public func remotePause() {
    if let handler = handleRemotePause {
      handler()
    } else {
      // Default behavior: pause the player
      onMainThread { player?.pause() }
    }
    onRemotePause()
  }

  public func remoteStop() {
    if let handler = handleRemoteStop {
      handler()
    } else {
      // Default behavior: stop the player
      onMainThread { player?.stop() }
    }
    onRemoteStop()
  }

  public func remotePlayPause() {
    // Toggle based on current state
    let isPlaying = onMainThread { player?.playWhenReady == true }
    if isPlaying {
      remotePause()
    } else {
      remotePlay()
    }
  }

  public func remoteNext() {
    if let handler = handleRemoteNext {
      handler()
    } else {
      // Default behavior: skip to next track
      onMainThread { player?.next() }
    }
    onRemoteNext()
  }

  public func remotePrevious() {
    if let handler = handleRemotePrevious {
      handler()
    } else {
      // Default behavior: skip to previous track
      onMainThread { player?.previous() }
    }
    onRemotePrevious()
  }

  public func remoteJumpForward(interval: Double) {
    let event = RemoteJumpForwardEvent(interval: interval)
    if let handler = handleRemoteJumpForward {
      handler(event)
    } else {
      // Default behavior: seek forward by interval
      onMainThread { player?.seekBy(interval) }
    }
    onRemoteJumpForward(event)
  }

  public func remoteJumpBackward(interval: Double) {
    let event = RemoteJumpBackwardEvent(interval: interval)
    if let handler = handleRemoteJumpBackward {
      handler(event)
    } else {
      // Default behavior: seek backward by interval
      onMainThread { player?.seekBy(-interval) }
    }
    onRemoteJumpBackward(event)
  }

  public func remoteSeek(position: Double) {
    logger.info("remoteSeek called with position: \(position)")
    let event = RemoteSeekEvent(position: position)
    if let handler = handleRemoteSeek {
      handler(event)
    } else {
      // Default behavior: seek to position
      logger.info("remoteSeek: calling player.seekTo(\(position))")
      onMainThread { player?.seekTo(position) }
    }
    onRemoteSeek(event)
  }

  public func remoteChangePlaybackPosition(position: Double) {
    remoteSeek(position: position)
  }

  public func remoteSetRating(rating _: Any) {
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

  public func remoteChangeRepeatMode(mode: RepeatMode) {
    // Apply the repeat mode change from CarPlay/lock screen
    try? setRepeatMode(mode: mode)
  }

  public func remoteChangeShuffleMode(enabled: Bool) {
    // Apply the shuffle mode change from CarPlay/lock screen
    try? setShuffleEnabled(enabled: enabled)
  }

  public func remoteChangePlaybackRate(rate: Float) {
    // Apply the playback rate change from CarPlay/lock screen
    player?.rate = rate
  }

  func playerDidChangeOptions(_: PlayerUpdateOptions) {
    // TODO: Convert to Options type
  }
}

// MARK: - Autolinking Alias

/// Alias for Nitro autolinking - expects class named "AudioBrowser"
public typealias AudioBrowser = HybridAudioBrowser
