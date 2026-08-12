import AVFoundation
import AVKit
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

  // MARK: - Private Properties

  /// Main-confined by convention, not annotation: `deinit` reads it as a
  /// safety net and can't hop. Reach it from `onMainActor` / `@MainActor`.
  private var player: TrackPlayer?
  private let networkMonitor = NetworkMonitor()
  private let playbackStateStore = PlaybackStateStore()
  let browserManager = BrowserManager()
  private let trackSelector: TrackSelector
  private var volumeObservation: NSKeyValueObservation?
  private var routeChangeObserver: NSObjectProtocol?
  private var interruptionObserver: NSObjectProtocol?
  private var mediaServicesResetObserver: NSObjectProtocol?
  /// Re-applies our resolved audio-session category. Captured at setup so a
  /// media-services reset (which clears the category) can restore it.
  @MainActor private var applyAudioSessionCategory: () -> Void = {}
  @MainActor private var nowPlayingOverride: NowPlayingUpdate?
  /// When false, the now-playing surface uses the raw track fields (override + formatter ignored).
  @MainActor private var nowPlayingMetadataEnabled = true
  /// Customizes the now-playing title/subtitle from the track + live timed metadata + playback state.
  @MainActor private var nowPlayingMetadataFormatter: ((_ params: FormatNowPlayingParams) -> Promise<NowPlayingUpdate?>)?
  /// Initial player state staged before the player exists — from setup options or the imperative
  /// setters called pre-setup. Strict last-write-wins; consumed when the player comes up.
  @MainActor private var pendingPlayWhenReady: Bool?
  @MainActor private var pendingRepeatMode: RepeatMode?
  /// Latest live timed (ICY/ID3) metadata, passed to the formatter. Cleared on track change.
  @MainActor private var latestTimedMetadata: TimedMetadata?
  @MainActor private let playerOptions = PlayerUpdateOptions()

  /// Configured playback rates for the playback-rate capability (for CarPlay rate cycling)
  @MainActor var playbackRates: [Double] { playerOptions.playbackRates }
  @MainActor var carPlayUpNextButton: Bool { playerOptions.carPlayUpNextButton }
  @MainActor var carPlayNowPlayingButtons: [CarPlayNowPlayingButton] {
    playerOptions.carPlayNowPlayingButtons
  }

  /// Written from the JS thread, the cooperative pool and MainActor, with a
  /// `didSet` that calls back into Nitro. MainActor-isolated so the compiler
  /// requires an `onMainActor` hop at every access.
  @MainActor private var lastNavigationError: NavigationError? {
    didSet {
      // Skip if both nil (no real change)
      guard oldValue != nil || lastNavigationError != nil else { return }
      navigationErrorEmitter.emit(NavigationErrorEvent(error: lastNavigationError))
    }
  }

  /// Isolated for the same reason as ``lastNavigationError``.
  @MainActor private var lastFormattedNavigationError: FormattedNavigationError? {
    didSet {
      // Skip if both nil (no real change)
      guard oldValue != nil || lastFormattedNavigationError != nil else { return }
      onFormattedNavigationError(lastFormattedNavigationError)
    }
  }

  // MARK: - Multi-Listener Emitters

  /// Emitters allow multiple listeners for each event type, avoiding callback hijacking
  public let tabsChangedEmitter = Emitter<[Track]>()
  public let contentChangedEmitter = Emitter<ResolvedTrack?>()
  public let favoriteChangedEmitter = Emitter<FavoriteChangedEvent>()
  public let activeTrackChangedEmitter = Emitter<PlaybackActiveTrackChangedEvent>()
  public let queueChangedEmitter = Emitter<[Track]>()
  public let navigationErrorEmitter = Emitter<NavigationErrorEvent>()
  public let repeatModeChangedEmitter = Emitter<RepeatModeChangedEvent>()
  public let shuffleChangedEmitter = Emitter<Bool>()
  public let externalContentChangedEmitter = Emitter<String>()
  /// Signals that the gate's active state changed (set or cleared). Carries
  /// the active flag; per-request chrome is obtained at each serve site via
  /// `gateDecision(for:)`, so the emitter only tells surfaces to re-render.
  public let gateChangedEmitter = Emitter<Bool>()
  /// Fired when a voice media intent (`handlePlayMediaIntent`) successfully
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
    get { onMainActor { browserManager.getTabs() } }
    set { /* tabs are managed internally by browserManager */ }
  }

  public var configuration: NativeBrowserConfiguration = .init(
    path: nil, request: nil, requestResolver: nil, browse: nil, browseResolver: nil, media: nil, artwork: nil, nowPlayingArtwork: nil, routes: nil,
    singleTrack: nil, handleTrackLoad: nil,
    androidControllerOfflineError: nil, carPlayLoadingTitle: nil, resolveAlbumUrl: nil, formatNavigationError: nil,
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
        let initialPath = configuredPath ?? tabs?.first?.url ?? "/"
        // Clear error before navigation (matches Kotlin clearNavigationError())
        clearNavigationError()
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
  /// resolver-error path in `gateDecision`. Matches Android's default.
  public var resolveGate: (NativeGateRequest) -> Promise<Promise<GateDecision>> = { _ in
    Promise.resolved(withResult: Promise.resolved(withResult: GateDecision(gated: true, gate: nil)))
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
  public var onSystemVolumeChanged: (Double) -> Void = { _ in } {
    didSet {
      setupVolumeObserver()
    }
  }

  public var onOutputChanged: (Output) -> Void = { _ in } {
    didSet {
      setupRouteChangeObserver()
    }
  }

  // MARK: - Initialization

  override public init() {
    trackSelector = TrackSelector(browserManager: browserManager)
    super.init()

    // Clean up the previous instance (e.g., on JS runtime reload).
    // The old HybridAudioBrowser may still be retained by the global
    // playerAndConfiguredBrowser OnceValue, so we must explicitly stop
    // its player to prevent two audio streams running simultaneously.
    onMainActor {
      HybridAudioBrowser.shared?.player?.destroy()
      playerAndConfiguredBrowser.reset()
      HybridAudioBrowser.shared = self
      // Tell connected external controllers (CarPlay) to re-subscribe against
      // this instance. Unconditional: `shared` is weak, so on a JS reload the
      // old instance may already be gone — "shared was nil" does not mean no
      // controller is subscribed to a dead instance's emitters.
      HybridAudioBrowser.instanceChangedEmitter.emit(())
    }
    setupEmitterToNitroForwarding()
    setupBrowserCallbacks()
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
    onMainActor {
      browserManager.onPathChanged = { [weak self] path in
        self?.onPathChanged(path)
      }
      browserManager.onContentChanged = { [weak self] content in
        self?.contentChangedEmitter.emit(content)
      }
      browserManager.onTabsChanged = { [weak self] tabs in
        self?.tabsChangedEmitter.emit(tabs)
      }
    }
  }

  private func handleNavigationError(_ error: Error, path: String) {
    let navError = NavigationError.from(error)

    onMainActor { lastNavigationError = navError }

    // Format the error (async if using JS callback, sync for defaults)
    let defaultFormatted = navError.defaultFormatted()
    if let formatter = onMainActor({ browserManager.config.formatNavigationError }) {
      let params = FormatNavigationErrorParams(error: navError, defaultFormatted: defaultFormatted, path: path)
      // Dispatch to main thread for the Nitro bridge call to avoid C++ noexcept crashes.
      // The result callbacks are @Sendable: a plain closure would inherit MainActor
      // isolation from this block, and Nitro resolves its promises synchronously on the
      // JS thread — Swift 6.2's dynamic isolation check then traps on closure entry.
      // They therefore hop back explicitly, like every other writer.
      DispatchQueue.main.async { [weak self] in
        formatter(params)
          .then { @Sendable [weak self] customDisplay in
            self?.setFormattedNavigationError(customDisplay ?? defaultFormatted)
          }
          .catch { @Sendable [weak self] _ in
            self?.setFormattedNavigationError(defaultFormatted)
          }
      }
    } else {
      setFormattedNavigationError(defaultFormatted)
    }
  }

  /// Callable from any thread — the Nitro promise callbacks below resolve on
  /// the JS thread.
  private func setFormattedNavigationError(_ formatted: FormattedNavigationError?) {
    onMainActor { lastFormattedNavigationError = formatted }
  }

  /// Synchronous on purpose: the `didSet`s notify JS, which has to happen
  /// before the navigation that may set them again.
  private func clearNavigationError() {
    onMainActor {
      lastNavigationError = nil
      lastFormattedNavigationError = nil
    }
  }

  // MARK: - Browser Methods

  public func navigatePath(path: String) throws {
    clearNavigationError()
    Task {
      do {
        try await browserManager.navigate(path)
      } catch {
        handleNavigationError(error, path: path)
      }
    }
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
        executePlayback(intent)
      case .intercepted:
        break
      case let .browse(url):
        navigateToBrowsableUrl(url)
      case .none:
        break
      }
    }
  }

  private func executePlayback(_ intent: TrackSelector.PlaybackIntent) {
    onMainActor {
      switch intent {
      case let .skipTo(index):
        do {
          try player?.skipTo(index, playWhenReady: true)
        } catch {
          logger.error("Failed to skip to track at index \(index): \(error)")
        }
      case let .setQueue(tracks, startIndex, sourcePath):
        player?.setQueue(tracks, initialIndex: startIndex, playWhenReady: true, sourcePath: sourcePath)
      case let .loadTrack(track):
        player?.load(track, playWhenReady: true)
      }
    }
  }

  private func navigateToBrowsableUrl(_ url: String) {
    clearNavigationError()
    Task {
      do {
        try await browserManager.navigate(url)
      } catch {
        handleNavigationError(error, path: url)
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
    onMainActor { browserManager.getContent() }
  }

  public func getNavigationError() throws -> NavigationError? {
    onMainActor { lastNavigationError }
  }

  public func getFormattedNavigationError() throws -> FormattedNavigationError? {
    onMainActor { lastFormattedNavigationError }
  }

  /// Internal signal sent on `externalContentChangedEmitter` to tell CarPlay to
  /// refresh every displayed template. Not a real path (paths are always
  /// `/…`), so it can't collide with one; kept internal — the public API is
  /// `invalidateAllContent()`.
  static let invalidateAllSentinel = "__rnab_invalidate_all__"

  public func notifyContentChanged(path: String) throws {
    onMainActor { browserManager.invalidateContentCache(path) }

    // Notify external controllers (CarPlay) that content changed
    externalContentChangedEmitter.emit(path)

    // Re-resolve the path if it's the current browser path
    if onMainActor({ browserManager.getPath() }) == path {
      Task {
        do {
          try await browserManager.navigate(path)
        } catch {
          handleNavigationError(error, path: path)
        }
      }
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
    Task {
      do {
        try await browserManager.refresh()
      } catch {
        handleNavigationError(error, path: onMainActor { browserManager.getPath() })
      }
    }
  }

  public func setFavorites(favorites: [String]) throws {
    onMainActor { browserManager.setFavorites(favorites) }
  }

  // MARK: - Gate

  /// The gate's default chrome, if one was set. Served when the resolver
  /// returns a gated decision with no per-request override, and for the static
  /// gate (no resolver). May be nil for a resolver-only gate.
  private(set) var defaultChrome: Gate?

  /// Whether JS installed a per-request resolver. When false the gate is static
  /// — every request is gated with `defaultChrome` and the JS hop is skipped.
  private(set) var hasResolver = false

  /// True once `setGate` runs, until `clearGate`. While active, external
  /// surfaces (CarPlay) keep their tabs visible but consult `gateDecision(for:)`
  /// per request, rendering the gate chrome where gated and refusing
  /// external-surface search. Playback, the queue and Now Playing are
  /// unaffected — a gate blocks finding content, never hearing it.
  private(set) var isGateActive = false

  /// The minimal built-in gate served when a request is gated but neither a
  /// per-request override nor a stored default chrome exists (resolver-only
  /// gate returning `true`).
  static let builtInGate = Gate(title: "Unavailable", message: nil)

  /// The outcome of a single gate decision: whether to gate, and (if so) the
  /// chrome to render.
  struct GateOutcome {
    let gated: Bool
    let chrome: Gate?
  }

  public func setGate(gate: Gate?, hasResolver: Bool) throws {
    onMainActor {
      defaultChrome = gate
      self.hasResolver = hasResolver
      isGateActive = true
      gateChangedEmitter.emit(true)
    }
  }

  public func clearGate() throws {
    onMainActor {
      guard isGateActive else { return }
      defaultChrome = nil
      hasResolver = false
      isGateActive = false
      gateChangedEmitter.emit(false)
    }
  }

  /// The single choke point for every gate enforcement site. Asks whether
  /// `request` should be gated and, if so, with what chrome (override → stored
  /// default → built-in). When no resolver is installed the gate is static and
  /// resolves without a JS hop.
  ///
  /// Resolver errors **fail CLOSED by design**: a thrown/rejected resolver, or a
  /// rejection at either `.await()` layer (bridge tear-down on JS reload, the JS
  /// runtime mid-reload, a serialization error) cannot prove the request is
  /// allowed — and a gate exists to withhold content, so serving on error would
  /// be a content leak (a paywall bypass). On error we gate with the stored
  /// default / built-in chrome so the user still sees a coherent gate page. A
  /// *successful* resolver returning `gated: false` still allows the request.
  @MainActor
  func gateDecision(for request: NativeGateRequest) async -> GateOutcome {
    guard isGateActive else { return GateOutcome(gated: false, chrome: nil) }
    if !hasResolver { return GateOutcome(gated: true, chrome: defaultChrome ?? Self.builtInGate) }
    guard let decision = try? await resolveGate(request).await().await() else {
      // Resolver error: fail CLOSED (see doc comment) — gate with the default /
      // built-in so the gate page still renders rather than leaking content.
      return GateOutcome(gated: true, chrome: defaultChrome ?? Self.builtInGate)
    }
    guard decision.gated else { return GateOutcome(gated: false, chrome: nil) } // explicit allow
    return GateOutcome(gated: true, chrome: decision.gate ?? defaultChrome ?? Self.builtInGate)
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
      // Configure the audio session from the iOS setup options (category, mode, route policy, and
      // options like `allowBluetooth` / `allowAirPlay`), but do NOT activate it here. Activating a
      // non-mixable `.playback` session interrupts other apps' audio — so activating at setup
      // pauses whatever the user is listening to (Safari, Spotify, a podcast) the instant our app
      // launches, before they play anything. Setting the category is silent; only activation
      // interrupts. The session is activated lazily when playback actually starts producing output
      // (see `playerDidChangePlayingState`) and re-activated on interruption-end. Best-effort: a
      // category-config failure must never brick setup.
      let session = AVAudioSession.sharedInstance()
      let cfg: (
        category: AVAudioSession.Category,
        mode: AVAudioSession.Mode,
        policy: AVAudioSession.RouteSharingPolicy,
        options: AVAudioSession.CategoryOptions,
      ) = options.ios?.resolveAudioSessionConfig()
        ?? (category: .playback, mode: .default, policy: .default, options: [])
      // Capture category application once so a media-services reset (which
      // clears the category) can restore the exact same config later.
      let applyCategory: () -> Void = {
        try? session.setCategory(cfg.category, mode: cfg.mode, policy: cfg.policy, options: cfg.options)
      }
      applyCategory()

      // Create player and configure on main actor
      await MainActor.run { [weak self] in
        guard let self else { return }
        self.applyAudioSessionCategory = applyCategory

        // Create player with self as callbacks delegate
        player = TrackPlayer(callbacks: self)

        // Apply buffer duration from options (in ms)
        if let buffer = options.ios?.buffer {
          player?.bufferDuration = buffer
        }

        // Apply retry configuration (top-level, cross-platform)
        if let retry = options.retry {
          player?.retryConfig = retry
        }

        // Wire up network monitor for accelerated retries when connectivity is restored
        player?.networkMonitor = networkMonitor

        // Central fan-out for connectivity changes (wired once here, idempotent on re-setup, and
        // independent of any JS subscription):
        //  - bridge to the JS `onOnlineChanged` event (no-op default until JS subscribes);
        //  - re-render now-playing so the formatter's offline/online label updates immediately on a
        //    connectivity change, not only on the next playback transition (fixes the stall-entry
        //    race where the device goes offline after the buffering line was already rendered);
        //  - reconnect a stalled stream when connectivity returns (see TrackPlayer.handleNetworkRestored).
        // The monitor only mutates state on the main thread, so this fires main-isolated.
        networkMonitor.onChanged = { [weak self] isOnline in
          MainActor.assumeIsolated {
            guard let self else { return }
            self.onOnlineChanged(isOnline)
            self.applyNowPlayingMetadata()
            if isOnline { self.player?.handleNetworkRestored() }
          }
        }

        // Observe audio-session interruptions so another app taking over audio
        // (or a phone call) reflects as a real pause rather than a stuck state.
        setupInterruptionObserver()

        // Observe media-server resets so a long-running stream recovers instead
        // of going permanently silent.
        setupMediaServicesResetObserver()

        // Configure media URL resolver
        player?.mediaLoader.mediaUrlResolver = { [weak self] src, track in
          guard let self else {
            return MediaResolvedUrl(url: src, headers: nil, userAgent: nil)
          }
          return await browserManager.resolveMediaUrl(src, track: track)
        }

        // Configure artwork URL resolver for Now Playing (with size context)
        player?.nowPlayingUpdater.artworkUrlResolver = { [weak self] track, imageContext in
          guard let self else { return nil }
          let perRoute = (track.id?.isEmpty == false) ? browserManager.config.nowPlayingArtwork : nil
          return await browserManager.resolveArtworkUrl(track: track, perRouteConfig: perRoute, imageContext: imageContext)
        }

        // Emit the JS `onNowPlayingChanged` event when the rendered text lines change. The updater
        // owns the system now-playing write; this shapes the JS metadata (elapsed time / artwork).
        player?.nowPlayingUpdater.onChanged = { [weak self] track, title, artist, album in
          guard let self else { return }
          self.onNowPlayingChanged(
            self.makeNowPlayingMetadata(track: track, title: title, artist: artist, album: album))
        }

        // Configure sleep timer callback
        player?.sleepTimerManager.onChanged = { [weak self] state in
          self?.onSleepTimerChanged(state)
        }

        // Now-playing: whether to manage metadata, and the optional formatter that owns the
        // rendered lines.
        nowPlayingMetadataEnabled = options.autoUpdateNowPlayingMetadata ?? true
        nowPlayingMetadataFormatter = options.nowPlayingMetadataFormatter
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

      // Propagate the favorite match mode to the browser so it can hydrate
      // row hearts (the `favorite` capability is the single favoriting switch).
      browserManager.setFavoriteMatch(playerOptions.capabilities.favoriteMatch)

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

  public func setPlaybackIntervalEnabled(enabled: Bool) {
    onMainActor { player?.setPlaybackIntervalEnabled(enabled) }
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
    onMainActor { player?.coordinator.nitroPlaybackError() }
  }

  public func retry() throws {
    // Re-resolve rather than replay the cached URL: short-lived URLs/tokens
    // may have expired since the failure (matches the internal retry path).
    onMainActor { player?.reloadResolving(startFromCurrentTime: true) }
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

  public func setSleepTimer(seconds: Double, fadeDuration: Double?) throws {
    onMainActor {
      player?.sleepTimerManager.set(seconds: seconds, fadeDuration: fadeDuration)
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
      // Optimistically reflect in the authoritative match set so the now-playing
      // heart (hydrated from it in getActiveTrack) flips immediately; native
      // reconciles to its canonical ids on the next setFavorites.
      browserManager.setFavorited(src: src, favorited: favorited)
      let updatedTrack = track.copying(favorited: favorited)
      favoriteChangedEmitter.emit(FavoriteChangedEvent(track: updatedTrack, favorited: favorited))
      // Fire active track changed so the now-playing heart + useActiveTrack() refresh.
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

  /// Whether the active (now-playing) track is favorited, per the authoritative
  /// favorite set (via `getActiveTrack`'s hydration).
  func isActiveTrackFavorited() -> Bool {
    (try? getActiveTrack())?.favorited ?? false
  }

  public func toggleActiveTrackFavorited() throws {
    try? setActiveTrackFavorited(favorited: !isActiveTrackFavorited())
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
    onMainActor {
      nowPlayingOverride = update
      applyNowPlayingMetadata()
    }
  }

  /// Transient now-playing fields (e.g. feedback for a refused remote
  /// command). Outranks the formatter and the override while active, so live
  /// metadata can't stomp it mid-flash. Reverted by a NATIVE timer — JS
  /// timers pause with a backgrounded host on Android, and the lock screen
  /// is exactly the backgrounded case — and cleared early on track change.
  @MainActor private var nowPlayingFlash: NowPlayingUpdate?
  @MainActor private var nowPlayingFlashRevert: DispatchWorkItem?

  /// Pending debounced audio-session release (see `playerShouldReleaseSession`).
  @MainActor private var sessionReleaseWork: DispatchWorkItem?

  public func flashNowPlaying(update: NowPlayingUpdate, durationMs: Double) throws {
    onMainActor {
      nowPlayingFlashRevert?.cancel()
      nowPlayingFlash = update
      let revert = DispatchWorkItem { [weak self] in
        guard let self else { return }
        self.nowPlayingFlash = nil
        self.nowPlayingFlashRevert = nil
        self.applyNowPlayingMetadata()
      }
      nowPlayingFlashRevert = revert
      DispatchQueue.main.asyncAfter(deadline: .now() + durationMs / 1000, execute: revert)
      applyNowPlayingMetadata()
    }
  }

  public func clearNowPlayingFlash() throws {
    onMainActor {
      guard nowPlayingFlash != nil else { return }
      cancelNowPlayingFlash()
      applyNowPlayingMetadata()
    }
  }

  @MainActor
  private func cancelNowPlayingFlash() {
    nowPlayingFlashRevert?.cancel()
    nowPlayingFlashRevert = nil
    nowPlayingFlash = nil
  }

  public func getNowPlaying() throws -> NowPlayingMetadata? {
    onMainActor {
      guard let track = player?.currentTrack else { return nil }
      let flash = nowPlayingFlash
      let override = nowPlayingOverride
      return makeNowPlayingMetadata(
        track: track,
        title: flash?.title ?? override?.title ?? track.title,
        artist: flash?.artist ?? override?.artist ?? track.artist,
        album: flash?.album ?? override?.album ?? track.album,
      )
    }
  }

  /// Returns the artwork URI suitable for JS consumption.
  /// Prefers the resolved artworkSource URI (e.g. file:// for rendered SF Symbols).
  /// Falls back to the raw artwork string only if it's not an SF Symbol (which JS can't load).
  private func nowPlayingArtwork(for track: Track) -> String? {
    if let uri = track.artworkSource?.uri { return uri }
    guard let artwork = track.artwork?.url else { return nil }
    return SFSymbolRenderer.isSFSymbol(artwork) ? nil : artwork
  }

  /// Builds the JS-facing now-playing metadata for the given rendered text lines.
  @MainActor
  private func makeNowPlayingMetadata(
    track: Track, title: String, artist: String?, album: String?,
  ) -> NowPlayingMetadata {
    NowPlayingMetadata(
      elapsedTime: player?.currentTime,
      title: title,
      album: album,
      artist: artist,
      duration: track.duration,
      artwork: nowPlayingArtwork(for: track),
      description: track.description,
      mediaId: track.src ?? track.url,
      genre: track.genre,
    )
  }

  /// Re-renders the now-playing surface via `NowPlayingUpdater`, handing it the current playback
  /// signals, override, and formatter. Called on every track / metadata / playback-state change.
  @MainActor
  private func applyNowPlayingMetadata() {
    guard let player, let track = player.currentTrack else { return }
    // Classify a stall by connectivity so the formatter can show "No internet connection" vs
    // "Reconnecting…" without a separate read. nil while not stalled.
    let stalled: StallReason? =
      player.isStalled ? (networkMonitor.getOnline() ? .buffering : .offline) : nil
    player.nowPlayingUpdater.render(
      track: track,
      timedMetadata: latestTimedMetadata,
      playWhenReady: player.playWhenReady,
      stalled: stalled,
      error: player.coordinator.nitroPlaybackError(),
      flash: nowPlayingFlash,
      override: nowPlayingMetadataEnabled ? nowPlayingOverride : nil,
      formatter: nowPlayingMetadataEnabled ? nowPlayingMetadataFormatter : nil,
    )
  }

  // MARK: - Network

  public func getOnline() throws -> Bool {
    networkMonitor.getOnline()
  }

  // MARK: - System Volume

  /// Sets up KVO observer for system volume changes
  private func setupVolumeObserver() {
    // Remove existing observation if any
    volumeObservation?.invalidate()

    let audioSession = AVAudioSession.sharedInstance()
    volumeObservation = audioSession.observe(\.outputVolume, options: [.new]) { [weak self] _, change in
      guard let newVolume = change.newValue else { return }
      self?.onSystemVolumeChanged(Double(newVolume))
    }
  }

  public func getSystemVolume() throws -> Double {
    Double(AVAudioSession.sharedInstance().outputVolume)
  }

  public func setSystemVolume(volume _: Double) throws {
    // iOS doesn't provide a public API to set system volume programmatically.
    // Users must adjust volume via hardware buttons or Control Center.
    logger.debug("setSystemVolume is not supported on iOS - volume must be adjusted via hardware buttons or Control Center")
  }

  // MARK: - External Audio Output

  /// Observes audio-session interruptions (phone calls, or another app such as
  /// Music/Spotify starting playback). iOS pauses our AVPlayer underneath us but
  /// doesn't update our playback state, so without this the UI stays stuck on
  /// "playing". On `.began` we reflect a real pause; on `.ended` we resume if we
  /// were playing and the system says we should.
  private func setupInterruptionObserver() {
    if let observer = interruptionObserver {
      NotificationCenter.default.removeObserver(observer)
    }

    interruptionObserver = NotificationCenter.default.addObserver(
      forName: AVAudioSession.interruptionNotification,
      object: nil,
      queue: .main,
    ) { [weak self] notification in
      self?.handleAudioSessionInterruption(notification)
    }
  }

  private func handleAudioSessionInterruption(_ notification: Notification) {
    guard
      let info = notification.userInfo,
      let typeValue = info[AVAudioSessionInterruptionTypeKey] as? UInt,
      let type = AVAudioSession.InterruptionType(rawValue: typeValue)
    else { return }

    switch type {
    case .began:
      // iOS 17+ fires a `.began` (with no matching `.ended`) when an output
      // route such as headphones disappears. Reflecting a pause here would
      // strand resume-intent state with nothing to clear it, and the
      // route-change observer already handles the output change — so ignore it.
      if #available(iOS 17.0, *),
         let reasonValue = info[AVAudioSessionInterruptionReasonKey] as? UInt,
         AVAudioSession.InterruptionReason(rawValue: reasonValue) == .routeDisconnected
      {
        return
      }
      onMainActor { player?.handleInterruptionBegan() }
    case .ended:
      let shouldResume = (info[AVAudioSessionInterruptionOptionKey] as? UInt)
        .map { AVAudioSession.InterruptionOptions(rawValue: $0).contains(.shouldResume) }
        ?? false
      onMainActor {
        // Reactivate the possibly-deactivated session before resuming — but
        // only when a resume will happen: activating while staying paused
        // grabs a non-mixable session with nothing to release it.
        if shouldResume, player?.willResumeAfterInterruption == true {
          try? AVAudioSession.sharedInstance().setActive(true)
        }
        player?.handleInterruptionEnded(shouldResume: shouldResume)
      }
    @unknown default:
      break
    }
  }

  /// Sets up observer for audio route changes
  private func setupRouteChangeObserver() {
    if let observer = routeChangeObserver {
      NotificationCenter.default.removeObserver(observer)
    }

    routeChangeObserver = NotificationCenter.default.addObserver(
      forName: AVAudioSession.routeChangeNotification,
      object: nil,
      queue: .main,
    ) { [weak self] notification in
      guard let self else { return }
      // The previous output disappeared (headphones unplugged, a Bluetooth
      // speaker powered off). Pause rather than abruptly resume out of the
      // built-in speaker — the platform convention every media app follows.
      // A deliberate pause (clears play intent), not an interruption: the user
      // restarts playback themselves once they want it.
      if let reasonValue = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
         AVAudioSession.RouteChangeReason(rawValue: reasonValue) == .oldDeviceUnavailable
      {
        // Also cancels a pending interruption auto-resume: unplugging during
        // a call must not blast the built-in speaker when the call ends.
        self.onMainActor { self.player?.handleRouteDisconnected() }
      }
      if let output = self.getCurrentOutput() {
        self.onOutputChanged(output)
      }
    }
  }

  /// Observes audio-server (`mediaserverd`) resets. When it fires, the session
  /// category is cleared and every AVPlayer/session handle is invalid — a
  /// long-running stream would otherwise go permanently silent until the app
  /// restarts.
  private func setupMediaServicesResetObserver() {
    if let observer = mediaServicesResetObserver {
      NotificationCenter.default.removeObserver(observer)
    }
    mediaServicesResetObserver = NotificationCenter.default.addObserver(
      forName: AVAudioSession.mediaServicesWereResetNotification,
      object: nil,
      queue: .main,
    ) { [weak self] _ in
      guard let self else { return }
      // Re-apply our category (the reset cleared it), then have the player
      // recreate itself and reconnect the current stream.
      self.onMainActor {
        self.applyAudioSessionCategory()
        self.player?.handleMediaServicesReset()
      }
    }
  }

  /// Gets the current audio output info
  private func getCurrentOutput() -> Output? {
    let session = AVAudioSession.sharedInstance()
    guard let output = session.currentRoute.outputs.first else { return nil }

    let (outputType, external): (OutputType, Bool) = switch output.portType {
    case .builtInSpeaker:
      (.speaker, false)
    case .builtInReceiver:
      (.receiver, false)
    case .airPlay:
      (.airplay, true)
    case .bluetoothA2DP, .bluetoothHFP, .bluetoothLE:
      (.bluetooth, true)
    case .headphones:
      (.headphones, true)
    case .carAudio:
      (.car, true)
    case .HDMI:
      (.hdmi, true)
    case .usbAudio:
      (.usb, true)
    default:
      (.other, true)
    }

    return Output(type: outputType, name: output.portName, external: external)
  }

  public func getOutput() throws -> Output? {
    getCurrentOutput()
  }

  public func supportsOutputSwitcher() throws -> Bool {
    // iOS can always present the system route picker (AVRoutePickerView).
    true
  }

  public func openOutputPicker() throws {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      guard let windowScene = UIApplication.shared.connectedScenes
        .compactMap({ $0 as? UIWindowScene })
        .first(where: { $0.activationState == .foregroundActive }),
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
    // Re-render so the formatter reflects the new state — notably the error line on a failure (the
    // coordinator sets playbackError before this fires). Redundant updates are deduped on publish.
    applyNowPlayingMetadata()
  }

  public func playerDidChangeActiveTrack(_ event: PlaybackActiveTrackChangedEvent) {
    // Clear now playing override + flash + live metadata when track changes
    // (matches Kotlin behavior; a flash must not carry over to a new track)
    nowPlayingOverride = nil
    cancelNowPlayingFlash()
    latestTimedMetadata = nil
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
    // Also notify now playing changed when track changes
    applyNowPlayingMetadata()
  }

  public func playerDidUpdateProgress(_ event: PlaybackProgressUpdatedEvent) {
    onPlaybackProgressUpdated(event)
  }

  public func playerDidFirePlaybackInterval() {
    onPlaybackInterval()
  }

  public func playerDidChangePlayWhenReady(_ playWhenReady: Bool) {
    // Intent to play again is the earliest cancel signal for a pending session release.
    if playWhenReady { cancelSessionRelease() }
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
      try? AVAudioSession.sharedInstance().setActive(true)
      // The now-playing session's activation request at link time predates this
      // (the audio session was still inactive, and the system may have declined);
      // re-request now so the surface can appear even if playback never produces
      // audio (first-ever load failing on a dead stream).
      player?.nowPlayingInfoController.reactivateSessionIfNeeded()
      // Output resumed — cancel any pending release armed by an earlier stop.
      cancelSessionRelease()
    }
    // Re-render so the formatter reacts to a stall starting or recovering (`stalled`) — a buffering
    // flag change that may not transition the coordinator state.
    applyNowPlayingMetadata()
  }

  public func playerShouldReleaseSession() {
    // Release the audio session so other apps' audio (Spotify, a podcast) can resume — debounced,
    // because iOS dislikes deactivating while our audio is still trailing off, and a quick
    // pause→resume shouldn't thrash the session. The coordinator only requests this on a genuine
    // stop (not an interruption, not mid-retry); a re-activation cancels it (see
    // `playerDidChangePlayWhenReady` and the activation above). Mirrors the now-playing flash timer.
    sessionReleaseWork?.cancel()
    let release = DispatchWorkItem { [weak self] in
      guard let self, self.player?.playWhenReady == false else { return }
      self.sessionReleaseWork = nil
      // `.notifyOthersOnDeactivation` signals other apps they may resume; without it the session
      // is released but nobody is told, so the user's audio would not come back.
      try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
    sessionReleaseWork = release
    DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: release)
  }

  @MainActor
  private func cancelSessionRelease() {
    sessionReleaseWork?.cancel()
    sessionReleaseWork = nil
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

  public func playerDidReceiveCommonMetadata(_ items: [AVMetadataItem]) {
    let metadata = TrackMetadata.from(items: items)
    onTrackMetadata(metadata)
  }

  public func playerDidReceiveTimedMetadata(_ groups: [AVTimedMetadataGroup]) {
    for group in groups {
      if let metadata = TimedMetadata.from(items: group.items) {
        latestTimedMetadata = metadata
        onTimedMetadata(metadata)
        // Re-render the now-playing so the formatter can reflect the live song.
        applyNowPlayingMetadata()
      }
    }
  }

  public func playerDidReceiveChapterMetadata(_ groups: [AVTimedMetadataGroup]) {
    let chapters = ChapterMetadata.from(groups: groups)
    onChapterMetadata(chapters)
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

  /// Static so the Siri intent handler can call it before the shared instance
  /// exists: it waits on the `playerAndConfiguredBrowser` gate, which resolves
  /// once RN boots. Uses the resolved `browser`, never `self`.
  static func handlePlayMediaIntent(criteria: MediaIntentCriteria, completion: @escaping @Sendable (Bool) -> Void) {
    Task { @MainActor in
      guard let (browser, player) = await playerAndConfiguredBrowser.wait(timeout: .seconds(8)) else {
        Logger(subsystem: "com.audiobrowser", category: "AudioBrowser")
          .error("handlePlayMediaIntent: browser/player not ready within budget")
        completion(false)
        return
      }
      // No-criteria intent ("play «app»") → resume. The gate must NOT block
      // this: the gate blocks *finding* content, never *hearing* the active or
      // persisted track. The gate is checked below, for the search branch only.
      if criteria.isResume {
        if player.currentTrack != nil {
          browser.logger.info("resume: warm — playing current track")
          player.play()
          browser.showNowPlayingRequestedEmitter.emit(())
          completion(true)
        } else if let state = browser.playbackStateStore.load() {
          browser.logger.info("resume: cold — restoring persisted track")
          let track = state.track.toNitro()
          let startMs = (track.live == true) ? nil : state.positionMs
          // Match Android resume: re-expand the full queue from the track's contextual
          // URL (parent container → siblings + selected index). Fall back to the single
          // track when the url isn't contextual or expansion fails.
          if let url = state.track.url,
             let expanded = try? await browser.browserManager.expandQueueFromContextualUrl(url)
          {
            player.setQueue(expanded.tracks, initialIndex: expanded.selectedIndex, startPositionMs: startMs, playWhenReady: true)
          } else {
            player.setQueue([track], initialIndex: 0, startPositionMs: startMs, playWhenReady: true)
          }
          browser.showNowPlayingRequestedEmitter.emit(())
          completion(true)
        } else {
          browser.logger.error("resume: nothing playing and nothing persisted → no-op")
          completion(false) // nothing playing and nothing persisted
        }
        return
      }

      // Assemble the Nitro SearchParams here (MainActor) from the criteria's
      // Sendable fields, so the structured mode/genre/… reach the request like
      // Android. Today the API still text-searches `q`; the rest is forward-compat.
      let params = SearchParams(
        mode: criteria.searchMode.flatMap { SearchMode(fromString: $0) },
        query: criteria.query,
        genre: criteria.genre,
        artist: criteria.artist,
        album: criteria.album,
        title: criteria.title,
        playlist: criteria.playlist,
        // .currentlyPlaying can't reach here — isResume routed it to resume.
        reference: criteria.reference == .my ? .my : .unknown,
      )

      // Search is *finding* new content — refused when the gate decides this
      // request is gated (resume above is unaffected: the gate never blocks
      // hearing, and the gate check stays after the resume check).
      let outcome = await browser.gateDecision(
        for: NativeGateRequest(reason: .search, path: nil, search: params),
      )
      guard !outcome.gated else {
        browser.logger.info("handlePlayMediaIntent: search refused — gated")
        browser.onGate(GateEvent(reason: .search))
        completion(false)
        return
      }

      do {
        guard let tracks = try await browser.browserManager.searchPlayable(params) else {
          completion(false)
          return
        }
        player.setQueue(tracks, initialIndex: 0, playWhenReady: true)
        browser.showNowPlayingRequestedEmitter.emit(())
        completion(true)
      } catch {
        browser.logger.error("handlePlayMediaIntent failed: \(error.localizedDescription)")
        completion(false)
      }
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
