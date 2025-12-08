import AVFoundation
import Foundation
import MediaPlayer
import NitroModules
import os.log

public class HybridAudioBrowser: HybridAudioBrowserSpec {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "AudioBrowser")
  // MARK: - Shared Instance for CarPlay

  /// Shared instance for CarPlay access. Set when HybridAudioBrowser is created.
  private(set) static weak var shared: HybridAudioBrowser?

  // MARK: - Private Properties

  private var player: TrackPlayer?
  private let networkMonitor = NetworkMonitor()
  let browserManager = BrowserManager()
  private var lastNavigationError: NavigationError? {
    didSet {
      // Skip if both nil (no real change)
      guard oldValue != nil || lastNavigationError != nil else { return }
      onNavigationError(NavigationErrorEvent(error: lastNavigationError))
    }
  }

  // MARK: - Thread Safety

  /// Executes a closure on the main thread synchronously, returning its result.
  /// If already on main thread, executes directly.
  private func onMainThread<T>(_ work: () -> T) -> T {
    if Thread.isMainThread {
      return work()
    } else {
      return DispatchQueue.main.sync { work() }
    }
  }

  /// Executes a throwing closure on the main thread synchronously.
  private func onMainThread<T>(_ work: () throws -> T) throws -> T {
    if Thread.isMainThread {
      return try work()
    } else {
      return try DispatchQueue.main.sync { try work() }
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

  public var configuration: NativeBrowserConfiguration = NativeBrowserConfiguration(
    path: nil, request: nil, media: nil, artwork: nil, routes: nil,
    singleTrack: nil, androidControllerOfflineError: nil
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

  public override init() {
    super.init()
    HybridAudioBrowser.shared = self
    setupBrowserCallbacks()
  }

  /// Returns the TrackPlayer instance, if setup has been called.
  func getPlayer() -> TrackPlayer? {
    return player
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
  }

  private func handleNavigationError(_ error: Error, path: String) {
    let navError: NavigationError
    if let browserError = error as? BrowserError {
      switch browserError {
      case .contentNotFound:
        navError = NavigationError(code: .contentNotFound, message: browserError.localizedDescription, statusCode: nil)
      case .httpError(let code, _):
        navError = NavigationError(code: .httpError, message: browserError.localizedDescription, statusCode: Double(code))
      case .networkError:
        navError = NavigationError(code: .networkError, message: browserError.localizedDescription, statusCode: nil)
      case .invalidConfiguration:
        navError = NavigationError(code: .unknownError, message: browserError.localizedDescription, statusCode: nil)
      }
    } else {
      navError = NavigationError(code: .unknownError, message: error.localizedDescription, statusCode: nil)
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
    if let url = url, BrowserPathHelper.isContextual(url) {
      Task {
        do {
          // Expand the queue from the contextual URL
          if let expanded = try await browserManager.expandQueueFromContextualUrl(url) {
            let (tracks, startIndex) = expanded

            // Replace queue and seek to selected track
            await MainActor.run {
              player?.clear()
              player?.add(tracks)
              try? player?.skipTo(startIndex)
              player?.play()
            }
          } else {
            // Fallback: just load the single track
            try load(track: track)
            try play()
          }
        } catch {
          logger.error("Error expanding queue: \(error.localizedDescription)")
          // Fallback to single track - playback errors reported via TrackPlayer callbacks
          try? load(track: track)
          try? play()
        }
      }
    }
    // If track has src, it's playable - load it
    else if track.src != nil {
      try load(track: track)
      try play()
    }
    // If track has url, it's browsable - navigate to it
    else if let url = url {
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
    return Promise.async { [weak self] in
      guard let self = self else { return [] }
      let resolved = try await self.browserManager.search(query)
      return resolved.children ?? []
    }
  }

  public func getContent() throws -> ResolvedTrack? {
    return browserManager.getContent()
  }

  public func getNavigationError() throws -> NavigationError? {
    return lastNavigationError
  }

  public func notifyContentChanged(path: String) throws {
    browserManager.invalidateContentCache(path)
    // Re-resolve the path if it's the current path
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

  public func setupPlayer(options: PartialSetupPlayerOptions) throws -> Promise<Void> {
    return Promise.async {
      // Configure audio session
      let session = AVAudioSession.sharedInstance()
      try session.setCategory(.playback, mode: .default)
      try session.setActive(true)

      // Create player with self as callbacks delegate
      self.player = TrackPlayer(callbacks: self)

      // Configure media URL resolver
      self.player?.mediaUrlResolver = { [weak self] src in
        guard let self = self else {
          return MediaResolvedUrl(url: src, headers: nil, userAgent: nil)
        }
        return await self.browserManager.resolveMediaUrl(src)
      }

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
    try onMainThread {
      guard let player = player else {
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
    return onMainThread { player?.playWhenReady ?? false }
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
    return onMainThread { Double(player?.volume ?? 1.0) }
  }

  public func setRate(rate: Double) throws {
    onMainThread { player?.rate = Float(rate) }
  }

  public func getRate() throws -> Double {
    return onMainThread { Double(player?.rate ?? 1.0) }
  }

  public func getProgress() throws -> Progress {
    return onMainThread {
      Progress(
        position: player?.currentTime ?? 0,
        duration: player?.duration ?? 0,
        buffered: player?.bufferedPosition ?? 0
      )
    }
  }

  public func getPlayback() throws -> Playback {
    return onMainThread { player?.getPlayback() ?? Playback(state: .none, error: nil) }
  }

  public func getPlayingState() throws -> PlayingState {
    return onMainThread {
      player?.playingStateManager.toPlayingState() ?? PlayingState(playing: false, buffering: false)
    }
  }

  public func getRepeatMode() throws -> RepeatMode {
    return onMainThread { player?.repeatMode ?? .off }
  }

  public func setRepeatMode(mode: RepeatMode) throws {
    onMainThread { player?.repeatMode = mode }
  }

  public func getPlaybackError() throws -> PlaybackError? {
    return onMainThread { player?.playbackError?.toNitroError() }
  }

  public func retry() throws {
    onMainThread { player?.reload(startFromCurrentTime: true) }
  }

  // MARK: - Sleep Timer

  public func getSleepTimer() throws -> SleepTimer {
    return onMainThread {
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
    return onMainThread {
      player?.sleepTimerManager.clear() ?? false
    }
  }

  // MARK: - Queue Management

  public func add(tracks: [Track], insertBeforeIndex: Double?) throws {
    try onMainThread {
      guard let player = player else { return }
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
      guard let player = player else { return }
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
        groupTitle: track.groupTitle
      )
      onFavoriteChanged(FavoriteChangedEvent(track: updatedTrack, favorited: favorited))
      // Fire active track changed so useActiveTrack() hook updates UI
      let position = player?.currentTime ?? 0
      onPlaybackActiveTrackChanged(PlaybackActiveTrackChangedEvent(
        lastIndex: Double(index),
        lastTrack: track,
        lastPosition: position,
        index: Double(index),
        track: updatedTrack
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
  }

  public func getQueue() throws -> [Track] {
    return onMainThread { player?.tracks ?? [] }
  }

  public func getTrack(index: Double) throws -> Track? {
    return onMainThread {
      guard let tracks = player?.tracks else { return nil }
      let i = Int(index)
      guard i >= 0, i < tracks.count else { return nil }
      return tracks[i]
    }
  }

  public func getActiveTrackIndex() throws -> Double? {
    return onMainThread {
      guard let index = player?.currentIndex, index >= 0 else { return nil }
      return Double(index)
    }
  }

  public func getActiveTrack() throws -> Track? {
    return onMainThread { player?.currentTrack }
  }

  // MARK: - Now Playing

  public func updateNowPlaying(update: NowPlayingUpdate?) throws {
    // TODO: Implement now playing override
  }

  public func getNowPlaying() throws -> NowPlayingMetadata? {
    return onMainThread {
      guard let track = player?.currentTrack else { return nil }
      return NowPlayingMetadata(
        elapsedTime: player?.currentTime,
        title: track.title,
        album: track.album,
        artist: track.artist,
        duration: track.duration,
        artwork: track.artwork,
        description: track.description,
        mediaId: track.src ?? track.url,
        genre: track.genre,
        rating: nil
      )
    }
  }

  // MARK: - Network

  public func getOnline() throws -> Bool {
    return networkMonitor.getOnline()
  }

  // MARK: - Equalizer (unsupported on iOS)

  public func getEqualizerSettings() throws -> EqualizerSettings? {
    // No-op: equalizer unsupported on iOS
    return nil
  }

  public func setEqualizerEnabled(enabled: Bool) throws {
    // No-op: equalizer unsupported on iOS
  }

  public func setEqualizerPreset(preset: String) throws {
    // No-op: equalizer unsupported on iOS
  }

  public func setEqualizerLevels(levels: [Double]) throws {
    // No-op: equalizer unsupported on iOS
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
    // Also notify now playing changed when track changes
    if let track = event.track {
      let nowPlaying = NowPlayingMetadata(
        elapsedTime: nil,
        title: track.title,
        album: track.album,
        artist: track.artist,
        duration: track.duration,
        artwork: track.artwork,
        description: track.description,
        mediaId: track.src ?? track.url,
        genre: track.genre,
        rating: nil
      )
      onNowPlayingChanged(nowPlaying)
    }
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
