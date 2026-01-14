import AVFoundation
import Foundation
import MediaPlayer
import NitroModules
import os.log

@MainActor let playerAndConfiguredBrowser = OnceValue<(HybridAudioBrowser, TrackPlayer)> {
  guard let browser = HybridAudioBrowser.shared,
        browser.browserManager.isConfigured,
        let player = browser.getPlayer() else { return nil }
  return (browser, player)
}

public class HybridAudioBrowser: HybridAudioBrowserSpec, @unchecked Sendable {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "AudioBrowser")

  // MARK: - Shared Instance for CarPlay

  /// Shared instance for CarPlay access. Set when HybridAudioBrowser is created.
  private(set) nonisolated(unsafe) weak static var shared: HybridAudioBrowser?

  // MARK: - Private Properties

  private var player: TrackPlayer?
  private let networkMonitor = NetworkMonitor()
  let browserManager = BrowserManager()
  private var nowPlayingOverride: NowPlayingUpdate?
  private let playerOptions = PlayerUpdateOptions()

  /// Configured playback rates for the playback-rate capability (for CarPlay rate cycling)
  var playbackRates: [Double] { playerOptions.playbackRates }
  private var lastNavigationError: NavigationError? {
    didSet {
      // Skip if both nil (no real change)
      guard oldValue != nil || lastNavigationError != nil else { return }
      navigationErrorEmitter.emit(NavigationErrorEvent(error: lastNavigationError))
    }
  }

  private var lastFormattedNavigationError: FormattedNavigationError? {
    didSet {
      // Skip if both nil (no real change)
      guard oldValue != nil || lastFormattedNavigationError != nil else { return }
      onFormattedNavigationError(lastFormattedNavigationError)
    }
  }

  // MARK: - Internal Callbacks (for CarPlay/external controllers)

  /// Called when notifyContentChanged is invoked, allowing CarPlay to refresh its templates.
  /// Set by CarPlayController during setup.
  var onExternalContentChanged: ((String) -> Void)?

  // MARK: - Multi-Listener Emitters

  /// Emitters allow multiple listeners for each event type, avoiding callback hijacking
  public let tabsChangedEmitter = Emitter<[Track]>()
  public let contentChangedEmitter = Emitter<ResolvedTrack?>()
  public let favoriteChangedEmitter = Emitter<FavoriteChangedEvent>()
  public let activeTrackChangedEmitter = Emitter<PlaybackActiveTrackChangedEvent>()
  public let queueChangedEmitter = Emitter<[Track]>()
  public let navigationErrorEmitter = Emitter<NavigationErrorEvent>()
  public let repeatModeChangedEmitter = Emitter<RepeatModeChangedEvent>()
  public let externalContentChangedEmitter = Emitter<String>()

  // MARK: - Thread Safety

  /// Executes a closure on the main actor synchronously, returning its result.
  /// Uses MainActor.assumeIsolated when already on main thread, otherwise dispatches synchronously.
  private func onMainActor<T: Sendable>(_ work: @MainActor () -> T) -> T {
    if Thread.isMainThread {
      MainActor.assumeIsolated { work() }
    } else {
      DispatchQueue.main.sync {
        MainActor.assumeIsolated { work() }
      }
    }
  }

  /// Executes a throwing closure on the main actor synchronously.
  private func onMainActor<T: Sendable>(_ work: @MainActor () throws -> T) throws -> T {
    if Thread.isMainThread {
      try MainActor.assumeIsolated { try work() }
    } else {
      try DispatchQueue.main.sync {
        try MainActor.assumeIsolated { try work() }
      }
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
    carPlayNowPlayingButtons: nil, formatNavigationError: nil,
  ) {
    didSet {
      browserManager.config = BrowserConfig(from: configuration)

      // Query tabs and navigate to initial path after config is set (matches Kotlin behavior)
      Task { @MainActor in
        // Notify waiting listeners (e.g., CarPlay cold start)
        playerAndConfiguredBrowser.check()
      }
      Task {
        let tabs = try? await browserManager.queryTabs()
        // Navigate to configured path, first tab, or "/"
        let initialPath = configuration.path ?? tabs?.first?.url ?? "/"
        // Clear error before navigation (matches Kotlin clearNavigationError())
        await MainActor.run {
          lastNavigationError = nil
          lastFormattedNavigationError = nil
        }
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
  public var onFormattedNavigationError: (FormattedNavigationError?) -> Void = { _ in }

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
    setupEmitterToNitroForwarding()
    setupBrowserCallbacks()
  }

  /// Returns the TrackPlayer instance, if setup has been called.
  func getPlayer() -> TrackPlayer? {
    player
  }

  /// Sets up automatic forwarding from emitters to Nitro callbacks
  private func setupEmitterToNitroForwarding() {
    tabsChangedEmitter.addListener { [weak self] tabs in
      self?.onTabsChanged(tabs)
    }

    contentChangedEmitter.addListener { [weak self] content in
      self?.onContentChanged(content)
    }

    favoriteChangedEmitter.addListener { [weak self] event in
      self?.onFavoriteChanged(event)
    }

    activeTrackChangedEmitter.addListener { [weak self] event in
      self?.onPlaybackActiveTrackChanged(event)
    }

    queueChangedEmitter.addListener { [weak self] tracks in
      self?.onPlaybackQueueChanged(tracks)
    }

    navigationErrorEmitter.addListener { [weak self] event in
      self?.onNavigationError(event)
    }

    repeatModeChangedEmitter.addListener { [weak self] event in
      self?.onPlaybackRepeatModeChanged(event)
    }
  }

  private func setupBrowserCallbacks() {
    browserManager.onPathChanged = { [weak self] path in
      self?.onPathChanged(path)
    }
    browserManager.onContentChanged = { [weak self] content in
      self?.contentChangedEmitter.emit(content)
    }
    browserManager.onTabsChanged = { [weak self] tabs in
      self?.tabsChangedEmitter.emit(tabs)
    }

    // Configure artwork URL resolver for transforming artwork URLs during browsing
    browserManager.artworkUrlResolver = { [weak browserManager] track, artworkConfig, imageContext in
      guard let browserManager else { return nil }
      return await browserManager.resolveArtworkUrl(track: track, perRouteConfig: artworkConfig, imageContext: imageContext)
    }
  }

  private func handleNavigationError(_ error: Error, path: String) {
    let navError = if let browserError = error as? BrowserError {
      switch browserError {
      case .contentNotFound:
        NavigationError(code: .contentNotFound, message: browserError.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
      case let .httpError(code, _):
        NavigationError(code: .httpError, message: browserError.localizedDescription, statusCode: Double(code), statusCodeSuccess: (200 ... 299).contains(code))
      case .networkError:
        NavigationError(code: .networkError, message: browserError.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
      case .invalidConfiguration:
        NavigationError(code: .unknownError, message: browserError.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
      case .callbackError:
        NavigationError(code: .callbackError, message: browserError.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
      }
    } else if let httpError = error as? HttpClient.HttpException {
      // HTTP error from HttpClient (non-2xx response)
      NavigationError(code: .httpError, message: httpError.localizedDescription, statusCode: Double(httpError.code), statusCodeSuccess: (200 ... 299).contains(httpError.code))
    } else if error is URLError {
      // Network error (connection failed, timeout, no internet, etc.)
      NavigationError(code: .networkError, message: error.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
    } else {
      NavigationError(code: .unknownError, message: error.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
    }

    lastNavigationError = navError

    // Format the error (async if using JS callback, sync for defaults)
    let defaultFormatted = defaultFormattedError(navError)
    if let formatter = browserManager.config.formatNavigationError {
      let params = FormatNavigationErrorParams(error: navError, defaultFormatted: defaultFormatted, path: path)
      formatter(params)
        .then { [weak self] customDisplay in
          self?.lastFormattedNavigationError = customDisplay ?? defaultFormatted
        }
        .catch { [weak self] _ in
          self?.lastFormattedNavigationError = defaultFormatted
        }
    } else {
      lastFormattedNavigationError = defaultFormatted
    }
  }

  private func defaultFormattedError(_ error: NavigationError) -> FormattedNavigationError {
    let title: String = switch error.code {
    case .contentNotFound:
      "Content Not Found"
    case .networkError:
      "Network Error"
    case .httpError:
      if let statusCode = error.statusCode {
        // Use system-localized HTTP status text (e.g., "Not Found", "Service Unavailable")
        HTTPURLResponse.localizedString(forStatusCode: Int(statusCode)).capitalized
      } else {
        "Server Error"
      }
    case .callbackError:
      "Error"
    case .unknownError:
      "Error"
    }
    return FormattedNavigationError(title: title, message: error.message)
  }

  // MARK: - Browser Methods

  public func navigatePath(path: String) throws {
    // Clear error synchronously before starting navigation (didSet notifies JS)
    lastNavigationError = nil
    lastFormattedNavigationError = nil
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
      let parentPath = BrowserPathHelper.stripTrackId(url)
      let trackId = BrowserPathHelper.extractTrackId(url)

      // Check if queue already came from this parent path - just skip to the track
      let existingIndex: Int? = onMainActor {
        guard let trackId,
              parentPath == player?.queueSourcePath,
              let index = player?.tracks.firstIndex(where: { $0.src == trackId })
        else { return nil }
        return index
      }
      if let index = existingIndex {
        logger.debug("Queue already from \(parentPath), skipping to index \(index)")
        onMainActor {
          try? player?.skipTo(index, playWhenReady: true)
        }
        return
      }

      Task {
        do {
          // Expand the queue from the contextual URL
          if let expanded = try await browserManager.expandQueueFromContextualUrl(url) {
            let (tracks, startIndex) = expanded

            // Replace queue and start at the selected track (auto-play)
            await MainActor.run {
              player?.setQueue(tracks, initialIndex: startIndex, playWhenReady: true, sourcePath: parentPath)
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
      onMainActor {
        player?.load(track, playWhenReady: true)
      }
    }
    // If track has url, it's browsable - navigate to it
    else if let url {
      // Clear error synchronously before starting navigation (didSet notifies JS)
      lastNavigationError = nil
      lastFormattedNavigationError = nil
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

  public func getFormattedNavigationError() throws -> FormattedNavigationError? {
    lastFormattedNavigationError
  }

  public func notifyContentChanged(path: String) throws {
    browserManager.invalidateContentCache(path)

    // Notify external controllers (CarPlay) that content changed
    onExternalContentChanged?(path)
    externalContentChangedEmitter.emit(path)

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

      // Create player and configure on main actor
      await MainActor.run { [weak self] in
        guard let self else { return }

        // Create player with self as callbacks delegate
        player = TrackPlayer(callbacks: self)

        // Configure media URL resolver
        player?.mediaUrlResolver = { [weak self] src in
          guard let self else {
            return MediaResolvedUrl(url: src, headers: nil, userAgent: nil)
          }
          return await browserManager.resolveMediaUrl(src)
        }

        // Configure artwork URL resolver for Now Playing (with size context)
        player?.artworkUrlResolver = { [weak self] track, imageContext in
          guard let self else { return nil }
          return await browserManager.resolveArtworkUrl(track: track, perRouteConfig: nil, imageContext: imageContext)
        }

        // Configure sleep timer callback
        player?.sleepTimerManager.onChanged = { [weak self] state in
          self?.onSleepTimerChanged(state)
        }

        // Apply default remote commands (play, pause, next, previous, seekTo)
        applyRemoteCommands()

        // Notify listeners that player is ready (e.g., CarPlay)
        playerAndConfiguredBrowser.check()
      }
    }
  }

  public func updateOptions(options: NativeUpdateOptions) {
    onMainActor {
      // Update stored options
      playerOptions.update(from: options)

      // Apply remote commands to player
      applyRemoteCommands()
    }
  }

  /// Converts capabilities to remote commands and applies them to the player
  @MainActor
  private func applyRemoteCommands() {
    guard let player else { return }

    let remoteCommands = playerOptions.capabilities.buildRemoteCommands(
      forwardJumpInterval: NSNumber(value: playerOptions.forwardJumpInterval),
      backwardJumpInterval: NSNumber(value: playerOptions.backwardJumpInterval),
      playbackRates: playerOptions.playbackRates,
    )

    player.remoteCommands = remoteCommands
  }

  public func getOptions() throws -> UpdateOptions {
    playerOptions.toUpdateOptions()
  }

  // MARK: - Playback Control

  public func load(track: Track) throws {
    try onMainActor {
      guard let player else {
        throw NSError(domain: "AudioBrowser", code: 1, userInfo: [NSLocalizedDescriptionKey: "Player not initialized"])
      }
      player.load(track)
    }
  }

  public func reset() throws {
    onMainActor { player?.clear() }
  }

  public func play() throws {
    onMainActor { player?.play() }
  }

  public func pause() throws {
    onMainActor { player?.pause() }
  }

  public func togglePlayback() throws {
    onMainActor { player?.togglePlayback() }
  }

  public func stop() throws {
    onMainActor { player?.stop() }
  }

  public func setPlayWhenReady(playWhenReady: Bool) throws {
    onMainActor { player?.playWhenReady = playWhenReady }
  }

  public func getPlayWhenReady() throws -> Bool {
    onMainActor { player?.playWhenReady ?? false }
  }

  public func seekTo(position: Double) throws {
    onMainActor { player?.seekTo(position) }
  }

  public func seekBy(offset: Double) throws {
    onMainActor { player?.seekBy(offset) }
  }

  public func setVolume(level: Double) throws {
    onMainActor { player?.volume = Float(level) }
  }

  public func getVolume() throws -> Double {
    onMainActor { Double(player?.volume ?? 1.0) }
  }

  public func setRate(rate: Double) throws {
    onMainActor { player?.rate = Float(rate) }
  }

  public func getRate() throws -> Double {
    onMainActor { Double(player?.rate ?? 1.0) }
  }

  public func getProgress() throws -> Progress {
    onMainActor {
      Progress(
        position: player?.currentTime ?? 0,
        duration: player?.duration ?? 0,
        buffered: player?.bufferedPosition ?? 0,
      )
    }
  }

  public func getPlayback() throws -> Playback {
    onMainActor { player?.getPlayback() ?? Playback(state: .none, error: nil) }
  }

  public func getPlayingState() throws -> PlayingState {
    onMainActor {
      player?.playingStateManager.toPlayingState() ?? PlayingState(playing: false, buffering: false)
    }
  }

  public func getRepeatMode() throws -> RepeatMode {
    onMainActor { player?.repeatMode ?? .off }
  }

  public func setRepeatMode(mode: RepeatMode) throws {
    onMainActor { player?.repeatMode = mode }
  }

  public func getShuffleEnabled() throws -> Bool {
    onMainActor { player?.shuffleEnabled ?? false }
  }

  public func setShuffleEnabled(enabled: Bool) throws {
    onMainActor { player?.shuffleEnabled = enabled }
  }

  public func getPlaybackError() throws -> PlaybackError? {
    onMainActor { player?.playbackError?.toNitroError() }
  }

  public func retry() throws {
    onMainActor { player?.reload(startFromCurrentTime: true) }
  }

  // MARK: - Sleep Timer

  public func getSleepTimer() throws -> SleepTimer {
    onMainActor {
      if let state = player?.sleepTimerManager.get() {
        return state
      }
      return .first(NullType.null)
    }
  }

  public func setSleepTimer(seconds: Double) throws {
    onMainActor {
      player?.sleepTimerManager.set(seconds: seconds)
    }
  }

  public func setSleepTimerToEndOfTrack() throws {
    onMainActor {
      player?.sleepTimerManager.setToEndOfTrack()
    }
  }

  public func clearSleepTimer() throws -> Bool {
    onMainActor {
      player?.sleepTimerManager.clear() ?? false
    }
  }

  // MARK: - Queue Management

  public func add(tracks: [Track], insertBeforeIndex: Double?) throws {
    try onMainActor {
      guard let player else { return }
      if let index = insertBeforeIndex {
        try player.add(tracks, at: Int(index))
      } else {
        player.add(tracks)
      }
    }
  }

  public func move(fromIndex: Double, toIndex: Double) throws {
    try onMainActor {
      try player?.move(fromIndex: Int(fromIndex), toIndex: Int(toIndex))
    }
  }

  public func remove(indexes: [Double]) throws {
    try onMainActor {
      guard let player else { return }
      // Remove in reverse order to maintain index validity
      for index in indexes.sorted().reversed() {
        try player.remove(Int(index))
      }
    }
  }

  public func removeUpcomingTracks() throws {
    onMainActor { player?.removeUpcomingTracks() }
  }

  public func skip(index: Double, initialPosition: Double?) throws {
    try onMainActor {
      try player?.skipTo(Int(index))
      if let position = initialPosition {
        player?.seekTo(position)
      }
    }
  }

  public func skipToNext(initialPosition: Double?) throws {
    onMainActor {
      player?.next()
      if let position = initialPosition {
        player?.seekTo(position)
      }
    }
  }

  public func skipToPrevious(initialPosition: Double?) throws {
    onMainActor {
      player?.previous()
      if let position = initialPosition {
        player?.seekTo(position)
      }
    }
  }

  public func setActiveTrackFavorited(favorited: Bool) throws {
    onMainActor {
      guard let track = player?.currentTrack, let src = track.src else { return }
      guard let index = player?.currentIndex, index >= 0 else { return }
      browserManager.updateFavorite(id: src, favorited: favorited)
      // Create updated track with new favorited state
      let updatedTrack = Track(
        url: track.url,
        src: track.src,
        artwork: track.artwork,
        artworkSource: track.artworkSource,
        artworkCarPlayTinted: track.artworkCarPlayTinted,
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
      // Update the track in the player's queue so getActiveTrack() returns correct state
      player?.setTrack(at: index, updatedTrack)
      favoriteChangedEmitter.emit(FavoriteChangedEvent(track: updatedTrack, favorited: favorited))
      // Fire active track changed so useActiveTrack() hook updates UI
      let position = player?.currentTime ?? 0
      activeTrackChangedEmitter.emit(PlaybackActiveTrackChangedEvent(
        lastIndex: Double(index),
        lastTrack: track,
        lastPosition: position,
        index: Double(index),
        track: updatedTrack,
      ))
    }
  }

  public func toggleActiveTrackFavorited() throws {
    onMainActor {
      guard let track = player?.currentTrack, let src = track.src else { return }
      // Check current favorited state from cache
      let currentTrack = browserManager.getCachedTrack(src)
      let isFavorited = currentTrack?.favorited ?? track.favorited ?? false
      try? setActiveTrackFavorited(favorited: !isFavorited)
    }
  }

  public func setQueue(tracks: [Track], startIndex: Double?, startPositionMs: Double?) throws {
    try onMainActor {
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
    onMainActor { player?.tracks ?? [] }
  }

  public func getTrack(index: Double) throws -> Track? {
    onMainActor {
      guard let tracks = player?.tracks else { return nil }
      let i = Int(index)
      guard i >= 0, i < tracks.count else { return nil }
      return tracks[i]
    }
  }

  public func getActiveTrackIndex() throws -> Double? {
    onMainActor {
      guard let index = player?.currentIndex, index >= 0 else { return nil }
      return Double(index)
    }
  }

  public func getActiveTrack() throws -> Track? {
    onMainActor { player?.currentTrack }
  }

  // MARK: - Now Playing

  public func updateNowPlaying(update: NowPlayingUpdate?) throws {
    onMainActor {
      nowPlayingOverride = update
      applyNowPlayingMetadata()
    }
  }

  public func getNowPlaying() throws -> NowPlayingMetadata? {
    onMainActor {
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
  @MainActor
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
    activeTrackChangedEmitter.emit(event)
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
    queueChangedEmitter.emit(tracks)
  }

  public func playerDidChangeRepeatMode(_ event: RepeatModeChangedEvent) {
    repeatModeChangedEmitter.emit(event)
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
      onMainActor { player?.play() }
    }
    onRemotePlay()
  }

  public func remotePause() {
    if let handler = handleRemotePause {
      handler()
    } else {
      // Default behavior: pause the player
      onMainActor { player?.pause() }
    }
    onRemotePause()
  }

  public func remoteStop() {
    if let handler = handleRemoteStop {
      handler()
    } else {
      // Default behavior: stop the player
      onMainActor { player?.stop() }
    }
    onRemoteStop()
  }

  public func remotePlayPause() {
    // Toggle based on current state
    let isPlaying = onMainActor { player?.playWhenReady == true }
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
      onMainActor { player?.next() }
    }
    onRemoteNext()
  }

  public func remotePrevious() {
    if let handler = handleRemotePrevious {
      handler()
    } else {
      // Default behavior: skip to previous track
      onMainActor { player?.previous() }
    }
    onRemotePrevious()
  }

  public func remoteJumpForward(interval: Double) {
    let event = RemoteJumpForwardEvent(interval: interval)
    if let handler = handleRemoteJumpForward {
      handler(event)
    } else {
      // Default behavior: seek forward by interval
      onMainActor { player?.seekBy(interval) }
    }
    onRemoteJumpForward(event)
  }

  public func remoteJumpBackward(interval: Double) {
    let event = RemoteJumpBackwardEvent(interval: interval)
    if let handler = handleRemoteJumpBackward {
      handler(event)
    } else {
      // Default behavior: seek backward by interval
      onMainActor { player?.seekBy(-interval) }
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
      onMainActor { player?.seekTo(position) }
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
    onMainActor { player?.rate = rate }
  }

  func playerDidChangeOptions(_: PlayerUpdateOptions) {
    // TODO: Convert to Options type
  }
}

// MARK: - Autolinking Alias

/// Alias for Nitro autolinking - expects class named "AudioBrowser"
public typealias AudioBrowser = HybridAudioBrowser
