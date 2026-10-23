import AVFoundation
import AVKit
import Foundation
import MediaPlayer
import NitroModules
import os.log

@MainActor let playerAndConfiguredBrowser = ReadyValue<(HybridAudioBrowser, Player)> {
  guard let browser = HybridAudioBrowser.shared,
        browser.browserManager.isConfigured,
        let player = browser.player else { return nil }
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
  let logger = Logger(subsystem: "com.audiobrowser", category: "AudioBrowser")

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
  var player: Player?
  /// The media stack under `player`, for what only AVFoundation has: now playing, buffering,
  /// volume and the remote commands.
  var engine: AVPlayerEngine?
  let networkMonitor = NetworkMonitor()
  let browserManager = BrowserManager()
  let browseGate = BrowseGate()
  let trackSelector: TrackSelector
  @MainActor private(set) lazy var audioSession = AudioSessionController()
  /// Initial player state staged before the player exists — from setup options or the imperative
  /// setters called pre-setup. Strict last-write-wins; consumed when the player comes up.
  @MainActor var pendingPlayWhenReady: Bool?
  @MainActor var pendingRepeatMode: RepeatMode?
  @MainActor let playerOptions = PlayerUpdateOptions()

  @MainActor lazy var navigationError = NavigationErrorState(
    onErrorChanged: { [weak self] in self?.onNavigationError(NavigationErrorEvent(error: $0)) },
    onFormattedChanged: { [weak self] in self?.onFormattedNavigationError($0) },
  )

  // MARK: - Emitters for Native Listeners

  public let favoriteChangedEmitter = Emitter<FavoriteChangedEvent>()
  public let activeTrackChangedEmitter = Emitter<PlaybackActiveTrackChangedEvent>()
  public let queueChangedEmitter = Emitter<[Track]>()
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
  func onMainActor<T>(_ work: @MainActor () -> T) -> T {
    if Thread.isMainThread {
      MainActor.assumeIsolated { UncheckedSendableBox(value: work()) }.value
    } else {
      DispatchQueue.main.sync {
        MainActor.assumeIsolated { UncheckedSendableBox(value: work()) }.value
      }
    }
  }

  func onMainActor<T>(_ work: @MainActor () throws -> T) throws -> T {
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
    get { onMainActor { browserManager.path } }
    set {
      guard let newPath = newValue else { return }
      navigate(newPath, clearError: false)
    }
  }

  public var tabs: [Track]? {
    get { onMainActor { browserManager.tabs } }
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
  /// init window where the gate is already `.resolved` but JS hasn't
  /// (re)bound the real resolver yet (JS reload / instance churn re-seeding gate
  /// state, e.g. CarPlay deciding its tabs at scene connect). Serving content
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
      // A new subscriber hears the current state at once.
      onOnlineChanged(networkMonitor.isOnline)
    }
  }

  public var onEqualizerChanged: (EqualizerSettings) -> Void = { _ in }
  public var onBatteryWarningPendingChanged: (BatteryWarningPendingChangedEvent) -> Void = { _ in }
  public var onBatteryOptimizationStatusChanged: (BatteryOptimizationStatusChangedEvent) -> Void = { _ in }
  public var onSystemVolumeChanged: (Double) -> Void = { _ in }

  public var onOutputChanged: (Output) -> Void = { _ in }

  public var onCarConnectedChanged: (Bool) -> Void = { _ in } {
    didSet {
      // Immediately notify current state (the scene may have connected before
      // this JS runtime subscribed).
      onCarConnectedChanged(HybridAudioBrowser.carPlayConnected)
    }
  }

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
      HybridAudioBrowser.shared?.destroyPlayer()
      HybridAudioBrowser.shared?.dropStaleJSCallbacks()
      HybridAudioBrowser.shared = self
      audioSession.outputChanged.addListener { [weak self] in self?.onOutputChanged($0) }
      audioSession.volumeChanged.addListener { [weak self] in self?.onSystemVolumeChanged($0) }
      // From the first setup on, whether or not JS subscribes: JS, then the player.
      networkMonitor.changed.addListener { [weak self] isOnline in
        MainActor.assumeIsolated {
          guard let self, let player else { return }
          onOnlineChanged(isOnline)
          player.networkStatusDidChange(isOnline: isOnline)
        }
      }
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
    forwardBrowserEventsToJS()
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
  /// strip would re-fire the config-setter cascade — another `configChanged`
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
    engine?.nowPlayingUpdater.formatter = nil
  }

  deinit {
    // Safety net: ensure the AVPlayer is stopped if this instance is deallocated.
    let player = self.player, engine = self.engine
    if Thread.isMainThread {
      MainActor.assumeIsolated {
        player?.clear()
        engine?.destroy()
      }
    } else {
      DispatchQueue.main.async {
        MainActor.assumeIsolated {
          player?.clear()
          engine?.destroy()
        }
      }
    }
  }

  /// Stops the player and releases its media stack: the AVPlayer and the now-playing session
  /// outlive a dropped reference otherwise.
  @MainActor
  func destroyPlayer() {
    player?.clear()
    engine?.destroy()
  }

  /// Forwards the browser's events to JS. The bridge's own events call JS where they are
  /// emitted; their emitters are for native listeners (CarPlay).
  private func forwardBrowserEventsToJS() {
    browserManager.pathChanged.addListener { [weak self] in self?.onPathChanged($0) }
    browserManager.tabsChanged.addListener { [weak self] in self?.onTabsChanged($0) }
    browserManager.contentChanged.addListener { [weak self] in self?.onContentChanged($0) }
  }
}

// MARK: - Autolinking Alias

/// Alias for Nitro autolinking - expects class named "AudioBrowser"
public typealias AudioBrowser = HybridAudioBrowser
