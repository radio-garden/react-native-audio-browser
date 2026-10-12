import AVFoundation
import AVKit
import Foundation
import MediaPlayer
import NitroModules
import os.log

@MainActor let playerAndConfiguredBrowser = ReadyValue<(HybridAudioBrowser, TrackPlayer)> {
  guard let browser = HybridAudioBrowser.shared,
        browser.browserManager.isConfigured,
        let player = browser.getPlayer() else { return nil }
  return (browser, player)
}

/// Wraps a non-Sendable value for passing through MainActor.assumeIsolated.
/// Only safe when the caller blocks until the MainActor work completes (DispatchQueue.main.sync).
private struct UncheckedSendableBox<T>: @unchecked Sendable { let value: T }

/// `@unchecked Sendable` because it must be: the spec's requirements are all
/// nonisolated and Nitro calls them synchronously on the JS thread, so the class
/// can't be `@MainActor`. Isolation is therefore per-property, and `@unchecked`
/// means the compiler won't tell you when a new one needs it.
public class HybridAudioBrowser: HybridAudioBrowserSpec, @unchecked Sendable {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "AudioBrowser")

  // MARK: - Shared Instance for CarPlay

  /// Shared instance for CarPlay access. Set when HybridAudioBrowser is created.
  private(set) nonisolated(unsafe) weak static var shared: HybridAudioBrowser?

  /// Fires when a new instance becomes `shared` (e.g. on a JS runtime reload),
  /// so connected external controllers (CarPlay) can re-subscribe — their
  /// listeners sit on the previous instance's emitters and would otherwise go
  /// silent.
  static let instanceChangedEmitter = Emitter<Void>()

  /// Installed once, on first instance creation. React Native posts this
  /// notification (from `RCTReloadCommand`) when a JS reload — dev or
  /// OTA-update restart — is triggered, *before* the old runtime is destroyed:
  /// the earliest signal that this instance's JS callbacks are about to go
  /// stale. `init` of the replacement instance drops them too, but only after
  /// the new runtime is up; this observer closes the window in between.
  /// Observed by name so the library needs no React-Core dependency (and
  /// stays inert under SPM tests, where nothing posts it).
  @MainActor private static let reloadObserver: NSObjectProtocol = NotificationCenter.default.addObserver(
    forName: Notification.Name("RCTTriggerReloadCommandNotification"),
    object: nil,
    queue: .main,
  ) { _ in
    MainActor.assumeIsolated {
      HybridAudioBrowser.shared?.dropStaleJSCallbacks()
    }
  }

  // MARK: - Private Properties

  /// Main-confined by convention, not annotation: `deinit` reads it as a
  /// safety net and can't hop. Reach it from `onMainActor` / `@MainActor`.
  private var player: TrackPlayer?
  private let networkMonitor = NetworkMonitor()
  let browserManager = BrowserManager()
  let browseGate = BrowseGate()
  private let trackSelector: TrackSelector
  @MainActor private(set) lazy var audioSession = AudioSessionController()
  /// Initial player state staged before the player exists — from setup options or the imperative
  /// setters called pre-setup. Strict last-write-wins; consumed when the player comes up.
  @MainActor private var pendingPlayWhenReady: Bool?
  @MainActor private var pendingRepeatMode: RepeatMode?
  @MainActor private let playerOptions = PlayerUpdateOptions()

  /// Configured playback rates for the playback-rate capability (for CarPlay rate cycling)
  @MainActor var playbackRates: [Double] { playerOptions.playbackRates }
  @MainActor var carPlayUpNextButton: Bool { playerOptions.carPlayUpNextButton }
  @MainActor var carPlayNowPlayingButtons: [CarPlayNowPlayingButton] {
    playerOptions.carPlayNowPlayingButtons
  }

  @MainActor var carPlayPlayingIndicatorLocation: CarPlayPlayingIndicatorLocation {
    playerOptions.carPlayPlayingIndicatorLocation
  }

  @MainActor private lazy var navigationError = NavigationErrorState(
    onErrorChanged: { [weak self] in self?.navigationErrorEmitter.emit(NavigationErrorEvent(error: $0)) },
    onFormattedChanged: { [weak self] in self?.onFormattedNavigationError($0) },
  )

  // MARK: - Multi-Listener Emitters

  /// Emitters allow multiple listeners for each event type, avoiding callback hijacking
  public let favoriteChangedEmitter = Emitter<FavoriteChangedEvent>()
  public let activeTrackChangedEmitter = Emitter<PlaybackActiveTrackChangedEvent>()
  public let queueChangedEmitter = Emitter<[Track]>()
  public let navigationErrorEmitter = Emitter<NavigationErrorEvent>()
  public let repeatModeChangedEmitter = Emitter<RepeatModeChangedEvent>()
  public let shuffleChangedEmitter = Emitter<Bool>()
  public let externalContentChangedEmitter = Emitter<String>()
  /// Fired when a voice media intent (`MediaIntentPlayback`) successfully
  /// starts playback. CarPlay surfaces the Now Playing template in response, so
  /// the user lands on the playing station (with the rest of the results in Up
  /// Next) rather than the screen they invoked Siri from. No-op where unobserved.
  public let showNowPlayingRequestedEmitter = Emitter<Void>()
  /// Fires after `updateOptions` applies a change, so external-surface managers
  /// (CarPlay) can refresh UI driven by now-runtime-updatable options
  /// (now-playing buttons, Up Next).
  public let playerOptionsChangedEmitter = Emitter<Void>()

  // MARK: - Thread Safety

  /// Runs a closure on MainActor synchronously. Safe for non-Sendable return types
  /// because DispatchQueue.main.sync blocks the caller — no concurrent access window.
  private func onMainActor<T>(_ work: @MainActor () -> T) -> T {
    if Thread.isMainThread {
      MainActor.assumeIsolated { UncheckedSendableBox(value: work()) }.value
    } else {
      DispatchQueue.main.sync {
        MainActor.assumeIsolated { UncheckedSendableBox(value: work()) }.value
      }
    }
  }

  private func onMainActor<T>(_ work: @MainActor () throws -> T) throws -> T {
    if Thread.isMainThread {
      try MainActor.assumeIsolated { try UncheckedSendableBox(value: work()) }.value
    } else {
      try DispatchQueue.main.sync {
        try MainActor.assumeIsolated { try UncheckedSendableBox(value: work()) }.value
      }
    }
  }

  // MARK: - Browser Properties

  public var path: String? {
    get { onMainActor { browserManager.getPath() } }
    set {
      guard let newPath = newValue else { return }
      navigate(newPath, clearError: false)
    }
  }

  public var tabs: [Track]? {
    get { onMainActor { browserManager.getTabs() } }
    set { /* tabs are managed internally by browserManager */ }
  }

  public var configuration: NativeBrowserConfiguration = .init(
    path: nil, request: nil, requestResolver: nil, browse: nil, browseResolver: nil, media: nil, artwork: nil, nowPlayingArtwork: nil, routes: nil,
    singleTrack: nil, handleTrackLoad: nil,
    androidControllerOfflineError: nil, viewMoreTitle: nil, carPlayLoadingTitle: nil, resolveAlbumPath: nil,
    formatNavigationError: nil,
  ) {
    didSet {
      // Copied here, on the setter's thread: the Task below outlives this call,
      // and Nitro can reassign `configuration` — a C++ struct holding
      // std::string/std::function — from the JS thread while it runs.
      let configuredPath = configuration.path
      onMainActor { browserManager.config = BrowserConfig(from: configuration) }

      // Query tabs and navigate to initial path after config is set (matches Kotlin behavior)
      Task { @MainActor in
        // Notify waiting listeners (e.g., CarPlay cold start)
        playerAndConfiguredBrowser.check()
      }
      Task {
        let tabs = try? await browserManager.queryTabs()
        // Navigate to configured path, first tab, or "/"
        navigate(configuredPath ?? tabs?.first?.path ?? "/", clearError: true)
      }
    }
  }

  // MARK: - Browser Callbacks

  public var onPathChanged: (String) -> Void = { _ in }
  public var onContentChanged: (ResolvedTrack?) -> Void = { _ in }
  public var onTabsChanged: ([Track]) -> Void = { _ in }
  public var onNavigationError: (NavigationErrorEvent) -> Void = { _ in }
  public var onFormattedNavigationError: (FormattedNavigationError?) -> Void = { _ in }

  /// Per-request gate decision, set by JS. Native calls it at a serve site to
  /// learn whether a browse path / search interaction should be gated and with
  /// what chrome. The double `Promise` mirrors every other native→JS
  /// value-returning callback (e.g. `searchCallback`): the outer resolves to the
  /// JS-side Promise, the inner to the decision.
  ///
  /// Default fails CLOSED (`gated: true`). This default is only consulted in the
  /// init window where `hasResolver` was already recorded true but JS hasn't
  /// (re)bound the real resolver yet (JS reload / instance churn re-seeding gate
  /// state, e.g. CarPlay re-seeding `isGated` at scene connect). Serving content
  /// in that window would be a fail-open leak — same direction as the
  /// resolver-error path in `BrowseGate.decide`. Matches Android's default.
  public var resolveGate: (NativeGateRequest) -> Promise<Promise<GateDecision>> = HybridAudioBrowser.defaultResolveGate

  // Computed, not stored: a nonisolated static holding a non-Sendable closure
  // is rejected under Swift 6 concurrency checking.
  static var defaultResolveGate: (NativeGateRequest) -> Promise<Promise<GateDecision>> {
    { _ in
      Promise.resolved(withResult: Promise.resolved(withResult: GateDecision(gated: true, gate: nil)))
    }
  }

  /// Fired when a request is gated (served the gate). Set by JS.
  public var onGate: (GateEvent) -> Void = { _ in }

  // MARK: - Player Callbacks

  public var onChapterMetadata: ([ChapterMetadata]) -> Void = { _ in }
  public var onTrackMetadata: (TrackMetadata) -> Void = { _ in }
  public var onTimedMetadata: (TimedMetadata) -> Void = { _ in }
  public var onPlaybackActiveTrackChanged: (PlaybackActiveTrackChangedEvent) -> Void = { _ in }
  public var onPlaybackError: (PlaybackErrorEvent) -> Void = { _ in }
  public var onPlaybackPlayWhenReadyChanged: (PlaybackPlayWhenReadyChangedEvent) -> Void = { _ in }
  public var onPlaybackPlayingState: (PlayingState) -> Void = { _ in }
  public var onPlaybackProgressUpdated: (PlaybackProgressUpdatedEvent) -> Void = { _ in }
  public var onPlaybackInterval: () -> Void = {}
  public var onPlaybackQueueEnded: (PlaybackQueueEndedEvent) -> Void = { _ in }
  public var onPlaybackQueueChanged: ([Track]) -> Void = { _ in }
  public var onPlaybackRepeatModeChanged: (RepeatModeChangedEvent) -> Void = { _ in }
  public var onPlaybackShuffleModeChanged: (Bool) -> Void = { _ in }
  public var onSleepTimerChanged: (SleepTimer?) -> Void = { _ in }
  public var onPlaybackChanged: (Playback) -> Void = { _ in }

  // MARK: - Remote Callbacks

  public var onRemoteJumpBackward: (RemoteJumpBackwardEvent) -> Void = { _ in }
  public var onRemoteJumpForward: (RemoteJumpForwardEvent) -> Void = { _ in }
  public var onRemoteNext: () -> Void = {}
  public var onRemotePause: () -> Void = {}
  public var onRemotePlay: () -> Void = {}
  public var onRemotePlayId: (RemotePlayIdEvent) -> Void = { _ in }
  public var onRemotePlaySearch: (RemotePlaySearchEvent) -> Void = { _ in }
  public var onRemotePrevious: () -> Void = {}
  public var onRemoteSeek: (RemoteSeekEvent) -> Void = { _ in }
  public var onRemoteSkip: (RemoteSkipEvent) -> Void = { _ in }
  public var onRemoteStop: () -> Void = {}

  // MARK: - Remote Handlers (optional overrides from JS)

  public var handleRemoteJumpBackward: ((RemoteJumpBackwardEvent) -> Void)?
  public var handleRemoteJumpForward: ((RemoteJumpForwardEvent) -> Void)?
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
      // The monitor->JS bridge is wired centrally in setupPlayer (so it doesn't depend on a JS
      // subscription and can also drive the now-playing re-render + player reconnect). Here we only
      // push the current state to a newly-attached subscriber.
      onOnlineChanged(networkMonitor.isOnline)
    }
  }

  public var onEqualizerChanged: (EqualizerSettings) -> Void = { _ in }
  public var onBatteryWarningPendingChanged: (BatteryWarningPendingChangedEvent) -> Void = { _ in }
  public var onBatteryOptimizationStatusChanged: (BatteryOptimizationStatusChangedEvent) -> Void = { _ in }
  public var onSystemVolumeChanged: (Double) -> Void = { _ in }

  public var onOutputChanged: (Output) -> Void = { _ in }

  // MARK: - Initialization

  override public init() {
    trackSelector = TrackSelector(browserManager: browserManager)
    super.init()

    // Clean up the previous instance (e.g., on JS runtime reload): stop its
    // player so two audio streams never run at once.
    onMainActor {
      // Statics are lazy — touch the observer so it's installed from the first
      // instance onward.
      _ = HybridAudioBrowser.reloadObserver
      HybridAudioBrowser.shared?.player?.destroy()
      HybridAudioBrowser.shared?.dropStaleJSCallbacks()
      HybridAudioBrowser.shared = self
      audioSession.outputChanged.addListener { [weak self] in self?.onOutputChanged($0) }
      audioSession.volumeChanged.addListener { [weak self] in self?.onSystemVolumeChanged($0) }
      browseGate.resolve = { [weak self] request in
        guard let self else { throw CancellationError() }
        return try await resolveGate(request).await().await()
      }
      // Tell connected external controllers (CarPlay) to re-subscribe against
      // this instance. Unconditional: `shared` is weak, so on a JS reload the
      // old instance may already be gone — "shared was nil" does not mean no
      // controller is subscribed to a dead instance's emitters.
      HybridAudioBrowser.instanceChangedEmitter.emit(())
    }
    setupEmitterToNitroForwarding()
  }

  /// Drops every JS-provided callback that native code can invoke on its own
  /// initiative (browse resolvers, `handleTrackLoad`, `resolveAlbumPath`,
  /// `formatNavigationError`, `resolveGate`, the now-playing formatter).
  ///
  /// Called on the *previous* instance when a new one takes over: its JS runtime
  /// is gone, and invoking a callback from a dead runtime aborts the process —
  /// Nitro's `AsyncJSCallback` throws on a destroyed Dispatcher, and the throw
  /// crosses the generated bridge wrapper's `noexcept` boundary. Native code
  /// (e.g. a CarPlay controller still bound to this instance mid-reload) keeps
  /// working off the data-only config with built-in fallbacks; `resolveGate`
  /// reverts to the fail-closed default it documents for exactly this window.
  /// One-shot: on a normal reload both triggers hit the same dying instance
  /// (the reload notification, then the replacement's `init`), and the second
  /// strip would re-fire the config-setter cascade — another `onConfigChanged`
  /// → CarPlay now-playing button rebuild — for nothing.
  @MainActor private var didDropJSCallbacks = false

  @MainActor
  private func dropStaleJSCallbacks() {
    guard !didDropJSCallbacks else { return }
    didDropJSCallbacks = true
    // Guarded: assigning `config` marks the browser configured (the setter's
    // didSet), and a never-configured browser must keep cold-start waiters
    // (CarPlay, Siri) blocked on the readiness gate rather than resolve them
    // against an empty config.
    if browserManager.isConfigured {
      browserManager.config = browserManager.config.strippingJSCallbacks()
    }
    resolveGate = Self.defaultResolveGate
    player?.nowPlayingUpdater.formatter = nil
  }

  deinit {
    // Safety net: ensure the AVPlayer is stopped if this instance is deallocated.
    let player = self.player
    if Thread.isMainThread {
      MainActor.assumeIsolated { player?.destroy() }
    } else {
      DispatchQueue.main.async { MainActor.assumeIsolated { player?.destroy() } }
    }
  }

  /// Returns the TrackPlayer instance, if setup has been called.
  func getPlayer() -> TrackPlayer? {
    player
  }

  /// Sets up automatic forwarding from emitters to Nitro callbacks
  private func setupEmitterToNitroForwarding() {
    browserManager.pathChanged.addListener { [weak self] in self?.onPathChanged($0) }
    browserManager.tabsChanged.addListener { [weak self] in self?.onTabsChanged($0) }
    browserManager.contentChanged.addListener { [weak self] in self?.onContentChanged($0) }

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

  /// Navigates the in-app browser, reporting a failure as the navigation error. A clear is
  /// synchronous: JS hears it before the navigation that may set the error again.
  private func navigate(_ path: String, clearError: Bool) {
    if clearError { onMainActor { navigationError.clear() } }
    Task { @MainActor in
      do {
        try await browserManager.navigate(path)
      } catch {
        await reportNavigationError(error, path: path)
      }
    }
  }

  @MainActor
  private func reportNavigationError(_ error: Error, path: String) async {
    await navigationError.report(NavigationError.from(error)) { [browserManager] in
      await browserManager.formatted($0, path: path)
    }
  }

  // MARK: - Browser Methods

  public func navigatePath(path: String) throws {
    navigate(path, clearError: true)
  }

  public func navigateTrack(track: Track) throws {
    // @MainActor: `player` is main-confined, and `trackSelector.select` is
    // MainActor anyway — a bare Task read the reference off the cooperative
    // pool, racing the assignment in `setupPlayer`.
    Task { @MainActor in
      guard let player else { return }
      let result = await trackSelector.select(track: track, player: player)
      switch result {
      case let .play(intent):
        player.execute(intent)
      case .intercepted:
        break
      case let .browse(path):
        navigate(path, clearError: true)
      case .none:
        break
      }
    }
  }

  public func search(query: String) throws -> Promise<[Track]> {
    Promise.async { [weak self] in
      guard let self else { return [] }
      let resolved = try await browserManager.search(query)
      return resolved.flattenedChildren ?? []
    }
  }

  public func getContent() throws -> ResolvedTrack? {
    onMainActor { browserManager.getContent() }
  }

  public func getNavigationError() throws -> NavigationError? {
    onMainActor { navigationError.error }
  }

  public func getFormattedNavigationError() throws -> FormattedNavigationError? {
    onMainActor { navigationError.formatted }
  }

  /// Internal signal sent on `externalContentChangedEmitter` to tell CarPlay to
  /// refresh every displayed template. Not a real path (paths are always
  /// `/…`), so it can't collide with one; kept internal — the public API is
  /// `invalidateAllContent()`.
  static let invalidateAllSentinel = "__rnab_invalidate_all__"

  public func notifyContentChanged(path: String) throws {
    // Throws on a contextual path (matching Android's `require`) — the caller
    // must pass the container path, or the invalidation silently hits nothing.
    try onMainActor { try browserManager.invalidateContentCache(path) }

    // Notify external controllers (CarPlay) that content changed
    externalContentChangedEmitter.emit(path)

    // Re-resolve the path if it's the current browser path
    if onMainActor({ browserManager.getPath() }) == path {
      navigate(path, clearError: false)
    }
  }

  public func invalidateAllContent() throws {
    onMainActor { browserManager.clearContentCache() }

    // Tell external controllers (CarPlay) to refresh every displayed template.
    externalContentChangedEmitter.emit(Self.invalidateAllSentinel)

    // Re-query the tabs source too: a callback source can resolve to new
    // titles after an invalidation (e.g. a locale switch). BrowserManager
    // emits tabsChanged only when the result actually differs, and a failed
    // re-query keeps the current tabs. (Android needs no equivalent — its
    // root re-query already runs queryTabs(); the web stub re-queries tabs on
    // the re-navigation.)
    Task {
      _ = try? await browserManager.queryTabs()
    }

    // Re-resolve the JS-facing current path with the cache cleared.
    Task { @MainActor in
      do {
        try await browserManager.refresh()
      } catch {
        await reportNavigationError(error, path: browserManager.getPath())
      }
    }
  }

  public func setFavorites(favorites: [String]) throws {
    onMainActor { browserManager.setFavorites(favorites) }
  }

  // MARK: - Gate

  public func setGate(gate: Gate?, hasResolver: Bool) throws {
    onMainActor { browseGate.set(chrome: gate, hasResolver: hasResolver) }
  }

  public func clearGate() throws {
    onMainActor { browseGate.clear() }
  }

  // MARK: - Car Connection (CarPlay)

  /// Static because the CarPlay scene can connect before the JS runtime
  /// creates the shared instance (cold start in the car).
  private nonisolated(unsafe) static var carPlayConnected = false

  /// Called by the CarPlay scene delegate (via `RNABCarPlayController`) on
  /// scene connect/disconnect — not from controller start/stop, which also
  /// cycle on a JS reload and would emit spurious connection changes.
  static func setCarPlayConnected(_ connected: Bool) {
    guard carPlayConnected != connected else { return }
    carPlayConnected = connected
    shared?.onCarConnectedChanged(connected)
  }

  public func isCarConnected() throws -> Bool {
    HybridAudioBrowser.carPlayConnected
  }

  public var onCarConnectedChanged: (Bool) -> Void = { _ in } {
    didSet {
      // Immediately notify current state (the scene may have connected before
      // this JS runtime subscribed).
      onCarConnectedChanged(HybridAudioBrowser.carPlayConnected)
    }
  }

  // MARK: - Player Setup

  public func setupPlayer(options: NativeSetupPlayerOptions) throws -> Promise<Void> {
    Promise.async {
      // Create player and configure on main actor
      await MainActor.run { [weak self] in
        guard let self else { return }
        // Set the category now, but activate only when playback produces output.
        self.audioSession.configure(options.ios)

        // A re-setup REPLACES the player, so the outgoing one has to be torn down
        // and un-published first. `TrackPlayer` has no deinit: dropping the
        // reference alone would leave its AVPlayer and MPNowPlayingSession alive.
        // No-op on first setup.
        player?.destroy()

        let newPlayer = TrackPlayer(callbacks: self, resolver: browserManager, networkMonitor: networkMonitor)
        if let buffer = options.ios?.buffer { newPlayer.bufferDuration = buffer }
        if let retry = options.retry { newPlayer.retryConfig = retry }
        newPlayer.nowPlayingUpdater.enabled = options.autoUpdateNowPlayingMetadata ?? true
        newPlayer.nowPlayingUpdater.formatter = options.nowPlayingMetadataFormatter
        newPlayer.nowPlayingUpdater.isOnline = networkMonitor.isOnline
        player = newPlayer
        audioSession.player = newPlayer

        // Connectivity fans out here, whether or not JS subscribes: the JS event, the
        // now-playing stall line, and reconnecting a stalled stream. The monitor mutates on main.
        networkMonitor.onChanged = { [weak self] isOnline in
          MainActor.assumeIsolated {
            self?.onOnlineChanged(isOnline)
            self?.player?.networkDidChange(isOnline: isOnline)
          }
        }

        // `options.keepSessionAliveOnError` is deliberately ignored here: iOS needs no masking.
        // A terminal error resolves the playback state to *paused*
        // (PlaybackCoordinator.applySideEffects), the now-playing info is retained (never cleared
        // on error), and next/previous stay enabled (gated on queue position, not state). So the
        // session stays controllable through errors by default — the behavior Android achieves via
        // InterceptingPlayer is already the iOS norm.

        // The bundled runtime options and initial state are part of the atomic launch
        // description. Stage the state first so setup options and earlier pre-setup setter
        // calls resolve by strict last-write-wins, then apply everything to the live player.
        if let bundled = options.options {
          updateOptions(options: bundled)
        } else {
          // updateOptions applies remote commands itself; without bundled options, apply the
          // defaults (play, pause, next, previous, seekTo) here.
          applyRemoteCommands()
        }
        if let mode = options.repeatMode { pendingRepeatMode = mode }
        if let intent = options.playWhenReady { pendingPlayWhenReady = intent }
        applyPendingPlayerState()

        // Push the resolved event cadence unconditionally. `updateOptions` applies
        // the interval only when it *differs* from the stored one, but the store
        // outlives the player — so on a re-setup with unchanged options the new
        // player would come up with no progress timer at all.
        player?.setProgressUpdateInterval(playerOptions.progressUpdateEventInterval)
        player?.setPlaybackIntervalEnabled(playbackIntervalEnabled)

        // Notify listeners that player is ready (e.g., CarPlay)
        playerAndConfiguredBrowser.check()
      }
    }
  }

  /// Applies initial player state staged before the player existed — from setup options or the
  /// imperative setters called pre-setup. Consumed on apply so a later re-setup doesn't replay
  /// stale state.
  @MainActor
  private func applyPendingPlayerState() {
    guard let player else { return }
    if let mode = pendingRepeatMode {
      player.repeatMode = mode
      pendingRepeatMode = nil
    }
    if let intent = pendingPlayWhenReady {
      player.playWhenReady = intent
      pendingPlayWhenReady = nil
    }
  }

  public func updateOptions(options: NativeUpdateOptions) {
    onMainActor {
      let previousInterval = playerOptions.progressUpdateEventInterval

      // Update stored options
      playerOptions.update(from: options)

      // Propagate the favorite flag to the browser so it can hydrate
      // row hearts (the `favorite` capability is the single favoriting switch).
      browserManager.setFavoriteEnabled(playerOptions.capabilities.favoriteEnabled)

      // Apply remote commands to player
      applyRemoteCommands()

      // Apply progress update interval if changed
      if playerOptions.progressUpdateEventInterval != previousInterval {
        player?.setProgressUpdateInterval(playerOptions.progressUpdateEventInterval)
      }

      onOptionsChanged(playerOptions.toOptions())
      playerOptionsChangedEmitter.emit(())
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

  public func getOptions() throws -> Options {
    onMainActor { playerOptions.toOptions() }
  }

  /// Stored so a re-setup can re-apply it: unlike the other runtime options this
  /// one has no home in `playerOptions`, and the player it was pushed to does not
  /// survive `setupPlayer`.
  @MainActor private var playbackIntervalEnabled = false

  public func setPlaybackIntervalEnabled(enabled: Bool) {
    onMainActor {
      playbackIntervalEnabled = enabled
      player?.setPlaybackIntervalEnabled(enabled)
    }
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
    onMainActor {
      // Pre-setup this stages the intent for the player to come up with — never a no-op.
      if let player { player.playWhenReady = playWhenReady }
      else { pendingPlayWhenReady = playWhenReady }
    }
  }

  public func getPlayWhenReady() throws -> Bool {
    onMainActor { player?.playWhenReady ?? pendingPlayWhenReady ?? false }
  }

  public func seekTo(position: Double) throws {
    onMainActor { player?.seekTo(position) }
  }

  public func seekBy(offset: Double) throws {
    onMainActor { player?.seekBy(offset) }
  }

  public func seekToLiveEdge() throws {
    onMainActor { player?.seekToLiveEdge() }
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
    onMainActor { player?.repeatMode ?? pendingRepeatMode ?? .off }
  }

  public func setRepeatMode(mode: RepeatMode) throws {
    onMainActor {
      // Pre-setup this stages the mode for the player to come up with — never a no-op.
      if let player { player.repeatMode = mode }
      else { pendingRepeatMode = mode }
    }
  }

  public func getShuffleEnabled() throws -> Bool {
    onMainActor { player?.shuffleEnabled ?? false }
  }

  public func setShuffleEnabled(enabled: Bool) throws {
    onMainActor { player?.shuffleEnabled = enabled }
  }

  public func getPlaybackError() throws -> PlaybackError? {
    onMainActor { player?.nitroPlaybackError }
  }

  public func retry() throws {
    // Re-resolve rather than replay the cached URL: short-lived URLs/tokens
    // may have expired since the failure (matches the internal retry path).
    onMainActor { player?.reloadResolving(startFromCurrentTime: true) }
  }

  // MARK: - Sleep Timer

  public func getSleepTimer() throws -> SleepTimer {
    onMainActor {
      player?.sleepTimer ?? .first(NullType.null)
    }
  }

  public func setSleepTimer(seconds: Double, fadeDuration: Double?) throws {
    onMainActor {
      player?.setSleepTimer(seconds: seconds, fadeDuration: fadeDuration)
    }
  }

  public func setSleepTimerToEndOfTrack() throws {
    onMainActor {
      player?.setSleepTimerToEndOfTrack()
    }
  }

  public func clearSleepTimer() throws -> Bool {
    onMainActor {
      player?.clearSleepTimer() ?? false
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
    try favoriteActiveTrack(favorited)
  }

  /// Sets the active track's favorite state: in the favorite set first, so the now-playing
  /// heart (hydrated from it) flips at once, then on the queued track. False when nothing is
  /// playing or the track has no identity.
  @discardableResult
  func favoriteActiveTrack(_ favorited: Bool) throws -> Bool {
    try onMainActor {
      guard let player, let identity = player.currentTrack?.identity else { return false }
      browserManager.setFavorited(identity: identity, favorited: favorited)
      guard let updated = try player.setActiveTrackFavorited(favorited) else { return false }
      // The only emit: a favorite toggle mutates the active track in place, so
      // onPlaybackActiveTrackChanged stays transition-only.
      favoriteChangedEmitter.emit(FavoriteChangedEvent(track: updated, favorited: favorited))
      return true
    }
  }

  /// Whether the active (now-playing) track is favorited, per the authoritative
  /// favorite set (via `getActiveTrack`'s hydration).
  func isActiveTrackFavorited() -> Bool {
    (try? getActiveTrack())?.favorited ?? false
  }

  public func toggleActiveTrackFavorited() throws {
    try favoriteActiveTrack(!isActiveTrackFavorited())
  }

  public func setQueue(tracks: [Track], startIndex: Double?, startPosition: Double?) throws {
    onMainActor {
      guard let player else { return }
      player.setQueue(
        tracks,
        initialIndex: startIndex.map { Int($0) } ?? 0,
        startPositionMs: startPosition.map { $0 * 1000 },
      )
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
    // Hydrate `favorited` from the authoritative favorite set — the now-playing
    // track is loaded outside the browse cache, so its own flag isn't otherwise
    // kept in sync as favorites change. This is the single source of truth for
    // "is the active track favorited" (now-playing heart, toggle direction, JS).
    onMainActor { player?.currentTrack.map(browserManager.hydrateFavorite) }
  }

  // MARK: - Now Playing

  public func updateNowPlaying(update: NowPlayingUpdate?) throws {
    onMainActor { player?.nowPlayingUpdater.setOverride(update) }
  }

  public func flashNowPlaying(update: NowPlayingUpdate, durationMs: Double) throws {
    onMainActor { player?.nowPlayingUpdater.flash(update, durationMs: durationMs) }
  }

  public func clearNowPlayingFlash() throws {
    onMainActor { player?.nowPlayingUpdater.clearFlash() }
  }

  public func getNowPlaying() throws -> NowPlayingMetadata? {
    onMainActor { player?.nowPlayingUpdater.nowPlaying }
  }

  // MARK: - Network

  public func getOnline() throws -> Bool {
    // No `onMainActor` hop: `isOnline` is lock-protected, so this stays a
    // cheap read on the JS thread instead of blocking on the main queue.
    networkMonitor.isOnline
  }

  // MARK: - System Volume

  public func getSystemVolume() throws -> Double {
    onMainActor { audioSession.volume }
  }

  public func setSystemVolume(volume _: Double) throws {
    // iOS doesn't provide a public API to set system volume programmatically.
    // Users must adjust volume via hardware buttons or Control Center.
    logger.debug("setSystemVolume is not supported on iOS - volume must be adjusted via hardware buttons or Control Center")
  }

  // MARK: - External Audio Output

  public func getOutput() throws -> Output? {
    onMainActor { audioSession.currentOutput }
  }

  public func supportsOutputSwitcher() throws -> Bool {
    // iOS can always present the system route picker (AVRoutePickerView).
    true
  }

  public func openOutputPicker() throws {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      guard let windowScene = UIApplication.shared.activeWindowScene,
            let window = windowScene.windows.first(where: { $0.isKeyWindow }),
            var topController = window.rootViewController
      else {
        self.logger.error("openOutputPicker: no active window scene / root view controller")
        return
      }

      // Walk up to the topmost presented view controller
      while let presented = topController.presentedViewController {
        topController = presented
      }

      // AVRoutePickerView has no public API to present its picker
      // programmatically, so we add it to the hierarchy and tap its internal
      // button. Two requirements that are easy to get wrong:
      //   1. The view must be *visible* to the system (a `isHidden` source view
      //      suppresses the presentation), so we keep it transparent instead.
      //   2. The internal button can be nested below the top-level subviews on
      //      modern iOS, so we search recursively rather than only direct children.
      let routePicker = AVRoutePickerView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
      routePicker.alpha = 0.01
      topController.view.addSubview(routePicker)

      let button = Self.firstButton(in: routePicker)
      self.logger.notice(
        "openOutputPicker: hierarchy=\(Self.describeHierarchy(routePicker), privacy: .public), buttonFound=\(button != nil, privacy: .public)",
      )

      if let button {
        button.sendActions(for: .touchUpInside)
      } else {
        self.logger.error("openOutputPicker: no UIButton found inside AVRoutePickerView")
      }

      // Keep the source view in the hierarchy long enough for the picker to
      // present (on iPad it anchors a popover to this view).
      DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
        routePicker.removeFromSuperview()
      }
    }
  }

  /// Depth-first search for the first `UIButton` inside a view tree.
  private static func firstButton(in view: UIView) -> UIButton? {
    if let button = view as? UIButton { return button }
    for subview in view.subviews {
      if let button = firstButton(in: subview) { return button }
    }
    return nil
  }

  /// Compact description of a view tree, e.g. `AVRoutePickerView[UIButton[UIImageView]]`.
  private static func describeHierarchy(_ view: UIView) -> String {
    let name = String(describing: type(of: view))
    let children = view.subviews.map(describeHierarchy).joined(separator: ", ")
    return children.isEmpty ? name : "\(name)[\(children)]"
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
    // The coordinator sets the error before this fires, so the error line renders.
    player?.nowPlayingUpdater.render()
  }

  public func playerDidChangeActiveTrack(_ event: PlaybackActiveTrackChangedEvent) {
    // Hydrate the active track's `favorited` from the authoritative set so JS
    // consumers (useActiveTrack) match getActiveTrack()'s hydrated value — the
    // coordinator emits the raw queue track, whose flag isn't kept in sync.
    let hydrated = PlaybackActiveTrackChangedEvent(
      lastIndex: event.lastIndex,
      lastTrack: event.lastTrack,
      lastPosition: event.lastPosition,
      index: event.index,
      track: event.track.map(browserManager.hydrateFavorite),
    )
    activeTrackChangedEmitter.emit(hydrated)
    player?.nowPlayingUpdater.trackChanged()
  }

  public func playerDidUpdateProgress(_ event: PlaybackProgressUpdatedEvent) {
    onPlaybackProgressUpdated(event)
  }

  public func playerDidFirePlaybackInterval() {
    onPlaybackInterval()
  }

  public func playerDidChangePlayWhenReady(_ playWhenReady: Bool) {
    // Intent to play again is the earliest cancel signal for a pending session release.
    if playWhenReady { audioSession.cancelRelease() }
    onPlaybackPlayWhenReadyChanged(PlaybackPlayWhenReadyChangedEvent(playWhenReady: playWhenReady))
  }

  public func playerDidChangePlayingState(_ state: PlayingState) {
    onPlaybackPlayingState(state)
    // Lazily activate the audio session the moment playback actually produces — or is buffering to
    // produce — output. This (not setup) is where we grab the session, so launching the app never
    // interrupts other apps' audio; the interruption only happens when the user truly starts
    // playback, which is correct. Keyed off play *state*, not `playWhenReady`, because the intent
    // can be set at setup (before any track) while output only begins here. Idempotent:
    // `setActive(true)` is a no-op when the session is already active.
    if state.playing || state.buffering {
      audioSession.activate()
      // The now-playing session's activation request at link time predates this
      // (the audio session was still inactive, and the system may have declined);
      // re-request now so the surface can appear even if playback never produces
      // audio (first-ever load failing on a dead stream).
      player?.reactivateNowPlayingSession()
    }
    // A stall starting or recovering may not change the coordinator state.
    player?.nowPlayingUpdater.render()
  }

  public func playerShouldReleaseSession() {
    audioSession.scheduleRelease()
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
    shuffleChangedEmitter.emit(enabled)
    onPlaybackShuffleModeChanged(enabled)
  }

  public func playerDidError(_ event: PlaybackErrorEvent) {
    onPlaybackError(event)
  }

  public func playerDidChangeSleepTimer(_ state: SleepTimer?) {
    onSleepTimerChanged(state)
  }

  public func playerDidChangeNowPlaying(_ metadata: NowPlayingMetadata) {
    onNowPlayingChanged(metadata)
  }

  public func playerDidReceiveCommonMetadata(_ items: [AVMetadataItem]) {
    let metadata = TrackMetadata.from(items: items)
    onTrackMetadata(metadata)
  }

  public func playerDidReceiveTimedMetadata(_ groups: [AVTimedMetadataGroup]) {
    for group in groups {
      if let metadata = TimedMetadata.from(items: group.items, icyCharset: player?.currentTrack?.icyCharset) {
        onTimedMetadata(metadata)
        player?.nowPlayingUpdater.receivedTimedMetadata(metadata)
      }
    }
  }

  public func playerDidReceiveChapterMetadata(_ groups: [AVTimedMetadataGroup]) {
    let chapters = ChapterMetadata.from(groups: groups)
    onChapterMetadata(chapters)
  }

  public func remotePlay() {
    dispatchRemote(handler: handleRemotePlay, report: onRemotePlay) { player?.play() }
  }

  public func remotePause() {
    dispatchRemote(handler: handleRemotePause, report: onRemotePause) { player?.pause() }
  }

  public func remoteStop() {
    dispatchRemote(handler: handleRemoteStop, report: onRemoteStop) { player?.stop() }
  }

  public func remotePlayPause() {
    if player?.playWhenReady == true { remotePause() } else { remotePlay() }
  }

  public func remoteNext() {
    dispatchRemote(handler: handleRemoteNext, report: onRemoteNext) { player?.next() }
  }

  public func remotePrevious() {
    dispatchRemote(handler: handleRemotePrevious, report: onRemotePrevious) { player?.previous() }
  }

  public func remoteJumpForward(interval: Double) {
    dispatchRemote(
      RemoteJumpForwardEvent(interval: interval), handler: handleRemoteJumpForward, report: onRemoteJumpForward,
    ) { player?.seekBy(interval) }
  }

  public func remoteJumpBackward(interval: Double) {
    dispatchRemote(
      RemoteJumpBackwardEvent(interval: interval), handler: handleRemoteJumpBackward, report: onRemoteJumpBackward,
    ) { player?.seekBy(-interval) }
  }

  public func remoteSeek(position: Double) {
    dispatchRemote(RemoteSeekEvent(position: position), handler: handleRemoteSeek, report: onRemoteSeek) {
      player?.seekTo(position)
    }
  }

  /// Runs JS's override for a remote command when it set one, else `fallback`, then reports
  /// the command to JS.
  private func dispatchRemote(handler: (() -> Void)?, report: () -> Void, fallback: () -> Void) {
    if let handler { handler() } else { fallback() }
    report()
  }

  private func dispatchRemote<Event>(
    _ event: Event,
    handler: ((Event) -> Void)?,
    report: (Event) -> Void,
    fallback: () -> Void,
  ) {
    if let handler { handler(event) } else { fallback() }
    report(event)
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
    player?.rate = rate
  }
}

// MARK: - Autolinking Alias

/// Alias for Nitro autolinking - expects class named "AudioBrowser"
public typealias AudioBrowser = HybridAudioBrowser
