import AVFoundation
import AVKit
import Foundation
import MediaPlayer
import NitroModules
import os.log

/// The shared browser and its player, ready once the browser is configured and the player is
/// set up; CarPlay and Siri wait on it.
@MainActor let playerAndConfiguredBrowser = ReadyValue<(HybridAudioBrowser, Player)> {
  guard let browser = HybridAudioBrowser.shared,
        browser.browserManager.isConfigured,
        let player = browser.player else { return nil }
  return (browser, player)
}

/// Carries a non-Sendable value out of `MainActor.assumeIsolated`. Safe only while the caller
/// blocks until the main-actor work completes.
private struct UncheckedSendableBox<T>: @unchecked Sendable { let value: T }

/// `@unchecked Sendable` because the spec's requirements are nonisolated and Nitro calls them
/// synchronously on the JS thread, so the class can't be `@MainActor`. Isolation is per-property,
/// and the compiler won't say when a new property needs it.
public class HybridAudioBrowser: HybridAudioBrowserSpec, @unchecked Sendable {
  let logger = Logger(subsystem: "com.audiobrowser", category: "AudioBrowser")

  // MARK: - Shared Instance for CarPlay

  private(set) nonisolated(unsafe) weak static var shared: HybridAudioBrowser?

  /// Fires when a new instance becomes `shared` (a JS reload), so CarPlay re-subscribes: its
  /// listeners on the previous instance's emitters would go silent.
  static let instanceChangedEmitter = Emitter<Void>()

  // React Native posts this when a JS reload starts, before the old runtime is destroyed: the
  // earliest sign this instance's JS callbacks go stale (the replacement's `init` drops them only
  // once the new runtime is up). Observed by name, so the library needs no React-Core dependency.
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

  // Main-confined by convention, not annotation: `deinit` reads it and can't hop. Reach it from
  // `onMainActor` or `@MainActor`.
  var player: Player?
  var engine: AVPlayerEngine?
  let networkMonitor = NetworkMonitor()
  let browserManager = BrowserManager()
  let browseGate = BrowseGate()
  let trackSelector: TrackSelector
  @MainActor private(set) var initialNavigation: Task<Void, Never>?
  @MainActor private(set) lazy var audioSession = AudioSessionController()
  // Player state set before the player exists, by setup options or the setters; last write wins,
  // consumed when the player comes up.
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
  /// Fires when a voice intent starts playback, so CarPlay shows the Now Playing template rather
  /// than the screen Siri was invoked from.
  public let showNowPlayingRequestedEmitter = Emitter<Void>()
  /// Fires after `updateOptions` applies a change, so CarPlay refreshes its now-playing buttons and
  /// Up Next.
  public let playerOptionsChangedEmitter = Emitter<Void>()

  // MARK: - Thread Safety

  /// Runs `work` on the main actor and waits for it; from another thread it blocks on
  /// `DispatchQueue.main.sync`, which is what makes a non-Sendable result safe to return.
  func onMainActor<T>(_ work: @MainActor () throws -> T) rethrows -> T {
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
    set { /* browserManager owns the tabs; JS cannot set them */ }
  }

  public var configuration: NativeBrowserConfiguration = .init(
    path: nil, request: nil, requestResolver: nil, browse: nil, browseResolver: nil, media: nil, artwork: nil, nowPlayingArtwork: nil, routes: nil,
    singleTrack: nil, handleTrackLoad: nil,
    androidControllerOfflineError: nil, viewMoreTitle: nil, carPlayLoadingTitle: nil, resolveAlbumPath: nil,
    formatNavigationError: nil,
  ) {
    didSet {
      // Copied on the setter's thread: the Task below outlives this call, and Nitro can reassign
      // `configuration` (a C++ struct holding std::string/std::function) from the JS thread.
      let configuredPath = configuration.path
      onMainActor {
        browserManager.config = BrowserConfig(from: configuration)
        browserManager.clearContent()
        initialNavigation?.cancel()
        initialNavigation = Task {
          let tabs = try? await browserManager.queryTabs()
          guard !Task.isCancelled else { return }
          navigate(configuredPath ?? tabs?.first?.path ?? "/", clearError: true)
        }
      }

      Task { @MainActor in
        playerAndConfiguredBrowser.check()
      }
    }
  }

  // MARK: - Browser Callbacks

  public var onPathChanged: (String) -> Void = { _ in }
  public var onContentChanged: (ResolvedTrack?) -> Void = { _ in }
  public var onTabsChanged: ([Track]) -> Void = { _ in }
  public var onNavigationError: (NavigationErrorEvent) -> Void = { _ in }
  public var onFormattedNavigationError: (FormattedNavigationError?) -> Void = { _ in }

  /// Whether a browse path or search interaction is gated, and with what gate; set by JS. The
  /// outer Promise resolves to JS's Promise, the inner to the decision.
  ///
  /// The default fails closed (`gated: true`). It answers only in the window where the gate is
  /// resolved but JS has not (re)bound its resolver (a JS reload, CarPlay deciding its tabs at
  /// scene connect); serving content there would leak, like the resolver-error path in
  /// `BrowseGate.decide`. Android defaults the same way.
  public var resolveGate: (NativeGateRequest) -> Promise<Promise<GateDecision>> = HybridAudioBrowser.defaultResolveGate

  static var defaultResolveGate: (NativeGateRequest) -> Promise<Promise<GateDecision>> {
    { _ in
      Promise.resolved(withResult: Promise.resolved(withResult: GateDecision(gated: true, gate: nil)))
    }
  }

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
      // A new subscriber hears the current state at once: the scene may have connected before
      // this JS runtime subscribed.
      onCarConnectedChanged(HybridAudioBrowser.carPlayConnected)
    }
  }

  // MARK: - Initialization

  override public init() {
    trackSelector = TrackSelector(browserManager: browserManager)
    super.init()

    // Stops the previous instance's player (a JS reload) so two streams never play at once.
    onMainActor {
      // Statics are lazy: touching the observer installs it from the first instance on.
      _ = HybridAudioBrowser.reloadObserver
      HybridAudioBrowser.shared?.destroyPlayer()
      HybridAudioBrowser.shared?.dropStaleJSCallbacks()
      HybridAudioBrowser.shared = self
      audioSession.outputChanged.addListener { [weak self] in self?.onOutputChanged($0) }
      audioSession.volumeChanged.addListener { [weak self] in self?.onSystemVolumeChanged($0) }
      // Registered whether or not JS subscribes: the player needs it too.
      networkMonitor.changed.addListener { [weak self] isOnline in
        guard let self else { return }
        MainActor.assumeIsolated {
          self.onOnlineChanged(isOnline)
          self.player?.networkStatusDidChange(isOnline: isOnline)
        }
      }
      browseGate.resolve = { [weak self] request in
        guard let self else { throw CancellationError() }
        return try await resolveGate(request).awaitNestedOrTimeout()
      }
      // Unconditional: `shared` is weak, so after a reload the old instance may be gone while
      // CarPlay is still subscribed to its emitters.
      HybridAudioBrowser.instanceChangedEmitter.emit(())
    }
    forwardBrowserEventsToJS()
  }

  @MainActor private var didDropJSCallbacks = false

  /// Drops every JS callback native code can invoke on its own (browse resolvers,
  /// `handleTrackLoad`, `resolveAlbumPath`, `formatNavigationError`, `resolveGate`, the now-playing
  /// formatter): calling into a dead runtime aborts the process (Nitro throws across a `noexcept`
  /// boundary). Native code still bound here (CarPlay mid-reload) keeps the data-only config, and
  /// `resolveGate` reverts to its fail-closed default. One-shot: the reload notification and the
  /// replacement's `init` both reach the dying instance, and a second strip would re-run the
  /// config setter's cascade.
  @MainActor
  private func dropStaleJSCallbacks() {
    guard !didDropJSCallbacks else { return }
    didDropJSCallbacks = true
    // Assigning `config` marks the browser configured, and a never-configured browser must keep
    // CarPlay and Siri waiting on `playerAndConfiguredBrowser`, not serve them an empty config.
    if browserManager.isConfigured {
      browserManager.config = browserManager.config.strippingJSCallbacks()
    }
    resolveGate = Self.defaultResolveGate
    engine?.nowPlayingUpdater.formatter = nil
  }

  deinit {
    // Safety net: the AVPlayer must not outlive this instance. `self` can't reach the main actor
    // from here, so the teardown captures what it releases.
    let player = self.player, engine = self.engine
    let tearDown: @Sendable () -> Void = { MainActor.assumeIsolated { Self.destroy(player, engine) } }
    if Thread.isMainThread { tearDown() } else { DispatchQueue.main.async(execute: tearDown) }
  }

  /// Stops the player and releases its media stack; the AVPlayer and the now-playing session
  /// otherwise outlive a dropped reference.
  @MainActor
  func destroyPlayer() {
    Self.destroy(player, engine)
  }

  @MainActor
  private static func destroy(_ player: Player?, _ engine: AVPlayerEngine?) {
    player?.clear()
    engine?.destroy()
  }

  // Only the browser's events: the bridge's own events call JS where they are emitted, and their
  // emitters serve native listeners (CarPlay).
  private func forwardBrowserEventsToJS() {
    browserManager.pathChanged.addListener { [weak self] in self?.onPathChanged($0) }
    browserManager.tabsChanged.addListener { [weak self] in self?.onTabsChanged($0) }
    browserManager.contentChanged.addListener { [weak self] in self?.onContentChanged($0) }
  }
}

// MARK: - Autolinking Alias

// Nitro autolinking expects a class named `AudioBrowser`.
public typealias AudioBrowser = HybridAudioBrowser
