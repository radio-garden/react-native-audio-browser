import CarPlay
import Foundation
import NitroModules
import os.log

/// Controller managing CarPlay templates and navigation.
///
/// Responsibilities:
/// - Creates and manages CPTabBarTemplate from browser tabs
/// - Converts browser content to CPListTemplate for navigation
/// - Handles item selection for playback and navigation
/// - Delegates image loading to CarPlayImageLoader
/// - Delegates Now Playing management to CarPlayNowPlayingManager
///
/// This class is exposed to Objective-C for use by RNABCarPlaySceneDelegate.
@MainActor
@objc(RNABCarPlayController)
public final class RNABCarPlayController: NSObject {
  // Force the linker to include RNABMediaIntentHandler so NSClassFromString can find it
  @objc public static let mediaIntentHandlerClass: AnyClass = RNABMediaIntentHandler.self

  private let logger = Logger(subsystem: "com.audiobrowser", category: "CarPlayController")

  private let interfaceController: CPInterfaceController
  private weak var audioBrowser: HybridAudioBrowser?
  private var trackSelector: TrackSelector?

  /// Track content subscriptions
  private var isStarted = false
  private var listenerRemovals: [() -> Void] = []

  /// Paths whose loading template has been pushed but not yet appeared. Guards a
  /// rapid double-tap from pushing the same destination twice (the first push is
  /// async, so `topTemplate` may not reflect it yet). Cleared when it appears.
  private var navigatingPaths: Set<String> = []

  /// Templates with a content load in flight. Guards a re-entrant load when a
  /// still-loading template re-appears (back-and-forth navigation) from spawning
  /// a second resolve + watchdog.
  private var loadingTemplates: Set<ObjectIdentifier> = []

  /// Tabs received while the user had templates pushed. Rebuilding the tab bar
  /// replaces the root template, which tears down the pushed navigation stack —
  /// so the rebuild is deferred until the user is back at the tab bar.
  private var pendingTabs: [Track]?

  /// Whether a gate is active. While set, tabs keep their tab-bar entries but
  /// each path is resolved per-request via `gateDecision(for:)`: a gated path
  /// renders the gate page instead of content; an allowed path shows real
  /// content. Mirrors `audioBrowser.isGateActive`; seeded in start() so a gate
  /// set before the scene connects renders at connect.
  private var isGated = false

  /// Drops a stale tab-bar build when a newer build supersedes it
  /// (latest-build-wins). Shared across BOTH the gated and non-gated `showTabBar`
  /// paths: each bumps and captures this at entry and bails before any
  /// `setRootTemplate` / `updateTemplates` once it's been bumped past their
  /// captured value. A build suspends the main actor at its `await` points
  /// (per-tab `gateDecision` when gated, first-tab `loadContent` when not), so a
  /// gate change can interleave a build of the opposite kind — most importantly a
  /// `clearGate` kicking the non-gated build while a gated build is mid-await.
  /// Covering both paths with one token means that clear-vs-gated interleave is
  /// caught too (not just gated-vs-gated), so a stale gated build can't paint a
  /// gate page over just-cleared content. Mirrors `albumArtistGeneration` in
  /// `CarPlayNowPlayingManager`.
  private var gateBuildGeneration: UInt = 0

  /// How long a browse resolve may run before the destination's loading spinner
  /// is replaced with an error state. The selection completion is fired
  /// immediately (so CarPlay never blocks the list — per Apple's async handler
  /// guidance we push the destination and fill it in), so this only bounds how
  /// long the *destination screen* spins. Backing out and re-tapping retries.
  private let resolveTimeout: Duration = .seconds(15)

  /// Helper object for CPInterfaceControllerDelegate conformance
  private var interfaceDelegate: InterfaceControllerDelegate?

  /// Image loading service for CarPlay artwork
  private var imageLoader: CarPlayImageLoader?

  /// List item and section factory
  private var listItemFactory: CarPlayListItemFactory?

  /// Now Playing template and button management
  private let nowPlayingManager: CarPlayNowPlayingManager

  /// Convenience accessor for browser config
  private var config: BrowserConfig {
    audioBrowser?.browserManager.config ?? BrowserConfig()
  }

  /// Whether a list item identifies the currently active (loaded) track.
  ///
  /// Compares track identities (`id` when non-blank, else `src`): the active
  /// track may have been loaded outside the browse tree (the consumer's own
  /// `load`/`setQueue`) with a `src` that differs textually from the browse
  /// row's (absolute vs relative, extra query params) while being the same
  /// item. A nil identity on either side never matches.
  private func isActiveTrack(identity: String?) -> Bool {
    guard let identity, let currentTrack = audioBrowser?.getPlayer()?.currentTrack else { return false }
    return currentTrack.identity == identity
  }

  // MARK: - Initialization

  @objc
  public init(interfaceController: CPInterfaceController) {
    self.interfaceController = interfaceController
    audioBrowser = HybridAudioBrowser.shared
    nowPlayingManager = CarPlayNowPlayingManager(interfaceController: interfaceController)
    super.init()
    nowPlayingManager.listItemFactory = { [weak self] track, handler in
      self?.listItemFactory?.createListItem(for: track, handler: handler)
        ?? CPListItem(text: track.title, detailText: nil)
    }
    nowPlayingManager.navigateToPath = { [weak self] path, title in
      Task { @MainActor in await self?.navigateToPath(path, title: title) }
    }
  }

  // MARK: - Lifecycle

  /// Reports CarPlay scene connection to the library (drives the JS-side
  /// `isCarPlayConnected` / `onCarPlayConnectedChanged`). Called by
  /// RNABCarPlaySceneDelegate on scene connect/disconnect — deliberately not
  /// from start()/stop(), which also cycle on a JS runtime reload.
  @objc
  public static func setConnected(_ connected: Bool) {
    HybridAudioBrowser.setCarPlayConnected(connected)
  }

  @objc
  public func start() {
    guard !isStarted else { return }
    isStarted = true

    logger.info("Starting CarPlay controller")

    // Set up interface controller delegate for template lifecycle events
    let delegate = InterfaceControllerDelegate(controller: self)
    interfaceDelegate = delegate
    interfaceController.delegate = delegate

    // Restart when a new HybridAudioBrowser replaces the shared instance
    // (JS runtime reload) — our subscriptions point at the old instance's
    // emitters and would otherwise go silent.
    let instanceToken = HybridAudioBrowser.instanceChangedEmitter.addListener { [weak self] _ in
      Task { @MainActor in
        self?.restart()
      }
    }
    listenerRemovals.append {
      HybridAudioBrowser.instanceChangedEmitter.removeListener(instanceToken)
    }

    // Show loading template while waiting
    showLoadingTemplate()

    // Wait for both browser and player to be ready
    Task { @MainActor in
      let (browser, _) = await playerAndConfiguredBrowser.wait()
      guard self.isStarted else { return }
      self.logger.debug("AudioBrowser and player ready, setting up CarPlay")
      self.audioBrowser = browser
      self.isGated = browser.isGateActive
      self.trackSelector = TrackSelector(browserManager: browser.browserManager)

      // Create image loader with CarPlay display traits
      self.imageLoader = CarPlayImageLoader(
        carTraitCollection: self.interfaceController.carTraitCollection,
        browserManager: browser.browserManager,
      )

      // Create list item factory
      let factory = CarPlayListItemFactory(
        isActiveTrack: { [weak self] identity in self?.isActiveTrack(identity: identity) ?? false },
        onItemSelected: { [weak self] track, completion in
          self?.handleItemSelection(track: track, completion: completion)
        },
      )
      factory.imageLoader = self.imageLoader
      self.listItemFactory = factory

      self.setupContentSubscriptions()

      // Wire up now playing manager
      self.nowPlayingManager.setup(audioBrowser: browser)

      await self.buildInitialInterface()
    }
  }

  @objc
  public func stop() {
    guard isStarted else { return }
    isStarted = false

    logger.info("Stopping CarPlay controller")

    // Remove all emitter listeners
    for removal in listenerRemovals {
      removal()
    }
    listenerRemovals.removeAll()

    // Clear config callback
    audioBrowser?.browserManager.onConfigChanged = nil
    audioBrowser?.browserManager.onFavoritesChanged = nil

    nowPlayingManager.teardown()
    listItemFactory = nil

    navigatingPaths.removeAll()
    loadingTemplates.removeAll()
    pendingTabs = nil
  }

  /// Full stop/start cycle. Used when the JS runtime reloads: the new
  /// HybridAudioBrowser instance replaces the emitters this controller is
  /// subscribed to and resets the readiness gate, so everything must be
  /// re-subscribed against the live instance (start() waits for it).
  @MainActor
  private func restart() {
    guard isStarted else { return }
    logger.info("AudioBrowser instance changed — restarting CarPlay controller")
    stop()
    start()
  }

  // MARK: - Content Subscriptions

  private func setupContentSubscriptions() {
    guard let audioBrowser else {
      logger.warning("AudioBrowser not available for CarPlay")
      return
    }

    // Subscribe to tab changes
    let tabsToken = audioBrowser.tabsChangedEmitter.addListener { [weak self] tabs in
      Task { @MainActor in
        self?.handleTabsChanged(tabs)
      }
    }
    listenerRemovals.append { [weak audioBrowser] in
      audioBrowser?.tabsChangedEmitter.removeListener(tabsToken)
    }

    // Subscribe to content changes
    let contentToken = audioBrowser.contentChangedEmitter.addListener { [weak self] content in
      Task { @MainActor in
        self?.handleContentChanged(content)
      }
    }
    listenerRemovals.append { [weak audioBrowser] in
      audioBrowser?.contentChangedEmitter.removeListener(contentToken)
    }

    // Subscribe to config changes (for Now Playing buttons and per-track
    // button state — e.g. resolveAlbumPath appearing/disappearing)
    audioBrowser.browserManager.onConfigChanged = { [weak self] _ in
      Task { @MainActor in
        self?.nowPlayingManager.setupNowPlayingButtons()
        self?.nowPlayingManager.updateNowPlayingButtonStates()
      }
    }

    // Now-playing buttons + Up Next are runtime-updatable via updateOptions();
    // refresh them when player options change (configureBrowser is no longer
    // the only path that can change them).
    let optionsToken = audioBrowser.playerOptionsChangedEmitter.addListener { [weak self] in
      Task { @MainActor in
        self?.nowPlayingManager.setupNowPlayingButtons()
        self?.nowPlayingManager.updateNowPlayingButtonStates()
      }
    }
    listenerRemovals.append { [weak audioBrowser] in
      audioBrowser?.playerOptionsChangedEmitter.removeListener(optionsToken)
    }

    // Refresh the Now Playing heart when favorites change externally (app /
    // webview). The favorite/active-track emitters only fire for the player's
    // own toggles, so without this an in-app favorite leaves the CarPlay heart
    // stale.
    audioBrowser.browserManager.onFavoritesChanged = { [weak self] in
      Task { @MainActor in
        self?.nowPlayingManager.updateFavoriteButtonState()
      }
    }

    // Subscribe to favorite changes (for Now Playing button)
    let favoriteToken = audioBrowser.favoriteChangedEmitter.addListener { [weak self] _ in
      Task { @MainActor in
        self?.nowPlayingManager.updateFavoriteButtonState()
      }
    }
    listenerRemovals.append { [weak audioBrowser] in
      audioBrowser?.favoriteChangedEmitter.removeListener(favoriteToken)
    }

    // Repeat/shuffle mode changes re-stamp the now-playing buttons' serialized
    // `isSelected` (a rebuild, skipped when unchanged) so CarPlay re-renders
    // draw the correct selected background on the first frame — see
    // CarPlayNowPlayingManager.BuiltButtonRow.
    let repeatToken = audioBrowser.repeatModeChangedEmitter.addListener { [weak self] _ in
      Task { @MainActor in
        self?.nowPlayingManager.setupNowPlayingButtons()
      }
    }
    listenerRemovals.append { [weak audioBrowser] in
      audioBrowser?.repeatModeChangedEmitter.removeListener(repeatToken)
    }
    let shuffleToken = audioBrowser.shuffleChangedEmitter.addListener { [weak self] _ in
      Task { @MainActor in
        self?.nowPlayingManager.setupNowPlayingButtons()
      }
    }
    listenerRemovals.append { [weak audioBrowser] in
      audioBrowser?.shuffleChangedEmitter.removeListener(shuffleToken)
    }

    // Subscribe to external content changes (notifyContentChanged /
    // invalidateAllContent).
    let externalContentToken = audioBrowser.externalContentChangedEmitter.addListener { [weak self] path in
      // Hop to the main actor like the sibling listeners — the emitter runs
      // listeners synchronously on the emitting (JS) thread.
      Task { @MainActor in
        if path == HybridAudioBrowser.invalidateAllSentinel {
          self?.invalidateAllContent()
        } else {
          self?.notifyContentChanged(path: path)
        }
      }
    }
    listenerRemovals.append { [weak audioBrowser] in
      audioBrowser?.externalContentChangedEmitter.removeListener(externalContentToken)
    }

    // Subscribe to gate changes (set / in-place update / clear)
    let gateToken = audioBrowser.gateChangedEmitter.addListener { [weak self] active in
      Task { @MainActor in
        self?.handleGateChanged(active)
      }
    }
    listenerRemovals.append { [weak audioBrowser] in
      audioBrowser?.gateChangedEmitter.removeListener(gateToken)
    }

    // Subscribe to active track changes (for playing indicator in lists)
    let activeTrackToken = audioBrowser.activeTrackChangedEmitter.addListener { [weak self] event in
      Task { @MainActor in
        self?.handleActiveTrackChanged(event)
      }
    }
    listenerRemovals.append { [weak audioBrowser] in
      audioBrowser?.activeTrackChangedEmitter.removeListener(activeTrackToken)
    }

    // Subscribe to queue changes (for Up Next list updates)
    let queueToken = audioBrowser.queueChangedEmitter.addListener { [weak self] tracks in
      Task { @MainActor in
        self?.nowPlayingManager.handleQueueChanged(tracks)
      }
    }
    listenerRemovals.append { [weak audioBrowser] in
      audioBrowser?.queueChangedEmitter.removeListener(queueToken)
    }

    // A voice media intent started playback — surface Now Playing so the user
    // lands on the playing station (Up Next holds the rest of the results).
    let showNowPlayingToken = audioBrowser.showNowPlayingRequestedEmitter.addListener { [weak self] _ in
      Task { @MainActor in
        self?.nowPlayingManager.showNowPlaying()
      }
    }
    listenerRemovals.append { [weak audioBrowser] in
      audioBrowser?.showNowPlayingRequestedEmitter.removeListener(showNowPlayingToken)
    }

    // Subscribe to navigation errors (from browser layer)
    let navErrorToken = audioBrowser.navigationErrorEmitter.addListener { [weak self] event in
      Task { @MainActor in
        self?.handleNavigationError(event)
      }
    }
    listenerRemovals.append { [weak audioBrowser] in
      audioBrowser?.navigationErrorEmitter.removeListener(navErrorToken)
    }
  }

  /// Handles navigation errors from the browser layer, displaying them in CarPlay
  @MainActor
  private func handleNavigationError(_ event: NavigationErrorEvent) {
    guard let error = event.error else { return }
    logger.warning("Navigation error: \(error.code.stringValue) - \(error.message)")
    // Derive the current path from the live top template (authoritative) rather
    // than a hand-maintained stack that goes stale on back navigation.
    let path = interfaceController.topTemplate.flatMap { getPath(from: $0) } ?? "/"
    showNavigationError(error, path: path)
  }

  // MARK: - Initial Interface

  @MainActor
  private func buildInitialInterface() async {
    guard let audioBrowser else {
      logger.error("AudioBrowser not available")
      await showRootNavigationError(
        NavigationError(code: .unknownError, message: "", statusCode: nil, statusCodeSuccess: nil),
      )
      return
    }

    // Get tabs from browser manager
    let tabs = audioBrowser.browserManager.getTabs()

    if let tabs, !tabs.isEmpty {
      await showTabBar(tabs: tabs)
    } else {
      // No tabs yet - query them
      logger.info("No tabs available, querying...")
      // Config exists by now (start() waited for it), so re-show the loading
      // root to pick up the app's localized loading title — the first loading
      // template was created before config was available.
      if config.carPlayLoadingTitle != nil {
        showLoadingTemplate()
      }
      do {
        // An empty query result routes to the .emptyContent error page inside
        // showTabBar, shared with runtime tab changes.
        let queriedTabs = try await audioBrowser.browserManager.queryTabs()
        await showTabBar(tabs: queriedTabs)
      } catch {
        logger.error("Failed to query tabs: \(error.localizedDescription)")
        await showRootNavigationError(NavigationError.from(error))
      }
    }
  }

  /// Shows a loading template as root while waiting for initialization. Before
  /// the browser is configured this can only show the spinner (iOS 18.4+) — the
  /// app's `carPlayLoadingTitle` isn't known yet; `buildInitialInterface`
  /// re-shows it once config is available.
  private func showLoadingTemplate() {
    let template = makeLoadingTemplate(title: nil, path: nil)
    interfaceController.safeSetRoot(template, animated: false)
  }

  // MARK: - Tab Bar

  @MainActor
  private func showTabBar(tabs: [Track]) async {
    // Serialize concurrent tab-bar builds across BOTH the gated and non-gated
    // paths with one shared generation token: bump at entry, capture, and bail
    // before any template mutation once a newer build supersedes us. Both paths
    // suspend the main actor at `await` points (per-tab `gateDecision` when
    // gated, first-tab `loadContent` when not), so a gate change can interleave
    // a build of the opposite kind — a `clearGate` kicking the non-gated build
    // while a gated build is mid-await, or vice versa. A single token covers the
    // clear-vs-gated interleave too, not just gated-vs-gated: the superseding
    // build bumps it, and the in-flight build bails before painting (preventing
    // a stale gate page over just-cleared content, or a clobbered root template).
    gateBuildGeneration &+= 1
    let generation = gateBuildGeneration

    // A zero-tab CPTabBarTemplate renders blank, with no copy, so empty routes
    // to the .emptyContent error page like every other failure (formatted
    // through the app's formatNavigationError, ADR 0001). The guard lives at
    // this choke point — both the initial build and runtime tab changes pass
    // through here — and sits before the gate check, since a gated build from
    // zero tabs is equally blank.
    guard !tabs.isEmpty else {
      await showRootNavigationError(
        NavigationError(code: .emptyContent, message: "", statusCode: nil, statusCodeSuccess: nil),
      )
      return
    }

    // While gated, each tab path is resolved per-request: a gated tab renders
    // the gate page with that request's chrome; an allowed tab shows real
    // content. This generalizes the old "all tabs show the gate" — a path the
    // resolver allows now shows real content.
    if isGated {
      await showGatedTabBar(tabs: tabs, generation: generation)
      return
    }

    logger.info("Building tab bar with \(tabs.count) tabs")

    let maxTabs = CPTabBarTemplate.maximumTabCount

    // Create tab templates synchronously (empty shells) - don't block on content loading
    let tabTemplates: [CPListTemplate] = tabs.prefix(maxTabs).map { tab in
      createTabTemplate(for: tab)
    }

    // A newer build (e.g. a gate raised mid-clear) superseded us — don't clobber
    // its root template.
    guard gateBuildGeneration == generation else { return }

    // Set the tab bar immediately so UI appears fast
    logger.info("Setting tab bar root template with \(tabTemplates.count) templates")
    let tabBar = CPTabBarTemplate(templates: tabTemplates)
    interfaceController.safeSetRoot(tabBar, animated: true)

    // Load content for the first tab only - others load lazily when selected
    if let firstTemplate = tabTemplates.first, let firstTab = tabs.first, let path = firstTab.path {
      await loadContent(for: path, into: firstTemplate)
    }
  }

  /// Builds the tab bar while a gate is active. Each tab's path is run through
  /// the single gate choke point (`gateDecision(for:)`): a gated tab becomes a
  /// gate page carrying that decision's chrome (and fires `onGate`); an allowed
  /// tab becomes a normal content shell that lazy-loads. With a static gate (no
  /// resolver) every tab is gated with the default chrome — today's behaviour.
  ///
  /// `generation` is the shared `gateBuildGeneration` captured by the calling
  /// `showTabBar`; we bail before any template mutation once a newer build (of
  /// either kind) bumps it past `generation`.
  @MainActor
  private func showGatedTabBar(tabs: [Track], generation: UInt) async {
    guard let audioBrowser else { return }

    guard !tabs.isEmpty else {
      // Tabs unknown (config not loaded yet, or none) — resolve the root path
      // and show a single gate page (or, if allowed, fall back to a normal
      // build once tabs arrive).
      let outcome = await audioBrowser.gateDecision(
        for: NativeGateRequest(reason: .browse, path: nil, search: nil),
      )
      guard gateBuildGeneration == generation else { return } // superseded by a newer build
      if outcome.gated {
        audioBrowser.onGate(GateEvent(reason: .browse))
        interfaceController.safeSetRoot(
          makeGateTemplate(gate: outcome.chrome, tab: nil), animated: true,
        )
      }
      return
    }

    let limitedTabs = Array(tabs.prefix(CPTabBarTemplate.maximumTabCount))
    var templates: [CPListTemplate] = []
    for tab in limitedTabs {
      let outcome = await audioBrowser.gateDecision(
        for: NativeGateRequest(reason: .browse, path: tab.path, search: nil),
      )
      guard gateBuildGeneration == generation else { return } // superseded by a newer build
      if outcome.gated {
        audioBrowser.onGate(GateEvent(reason: .browse))
        templates.append(makeGateTemplate(gate: outcome.chrome, tab: tab))
      } else {
        // This path is allowed even though a gate is active — show real content.
        templates.append(createTabTemplate(for: tab))
      }
    }

    if let tabBar = interfaceController.rootTemplate as? CPTabBarTemplate,
       tabBar.templates.count == templates.count
    {
      // Equal-count in-place swap keeps the selected tab index.
      tabBar.updateTemplates(templates)
    } else {
      interfaceController.safeSetRoot(
        CPTabBarTemplate(templates: templates), animated: true,
      )
    }

    // Eagerly fill any allowed (content) tabs: templateDidAppear isn't
    // guaranteed to re-fire for templates swapped in via updateTemplates.
    for (tab, template) in zip(limitedTabs, templates) {
      guard getPath(from: template) != nil, let path = tab.path else { continue }
      await loadContent(for: path, into: template)
    }
  }

  /// Creates a tab template shell without loading content (synchronous).
  /// The shell carries the loading state (spinner / `carPlayLoadingTitle`)
  /// until its content lazy-loads on first appearance.
  private func createTabTemplate(for track: Track) -> CPListTemplate {
    // The path stored on the template drives lazy loading and refresh.
    let template = makeLoadingTemplate(title: track.title, path: track.path)
    applyTabBarEntry(to: template, for: track)
    return template
  }

  /// Stamps a template's tab-bar entry: title and image (default, SF Symbol,
  /// or loaded artwork). Shared by content tabs and gate-page tabs so a gate
  /// keeps the familiar tab bar.
  private func applyTabBarEntry(to template: CPTemplate, for track: Track) {
    // Set tab title explicitly (required for tab bar display)
    template.tabTitle = track.title

    // Set tab image only when the tab supplies one (SF Symbol / artwork /
    // artworkSource). A tab with no icon stays title-only — CPTemplate renders
    // fine with just `tabTitle`. Tab bar icons are 24pt x 24pt per the CarPlay
    // Developer Guide: https://developer.apple.com/download/files/CarPlay-Developer-Guide.pdf
    if let artwork = track.artwork?.url, SFSymbolRenderer.isSFSymbol(artwork) {
      let (symbolName, _, _) = SFSymbolRenderer.parseArtwork(artwork)
      if let image = imageLoader?.sfSymbolImage(symbolName) {
        template.tabImage = image
      }
    } else if track.artwork != nil || track.artworkSource != nil {
      // loadArtwork handles both artwork and artworkSource
      let tabImageSize = CGSize(width: 24, height: 24)
      imageLoader?.loadArtwork(for: track, size: tabImageSize) { [weak template] image in
        Task { @MainActor in
          if let image {
            template?.tabImage = image
          }
        }
      }
    } else {
      // No icon supplied: clear any prior image so a restamp (handleTabsChanged)
      // of an existing template doesn't leave a stale icon on a now title-only tab.
      template.tabImage = nil
    }
  }

  // MARK: - List Templates

  /// Finds the path associated with a template, if any
  private func getPath(from template: CPTemplate) -> String? {
    (template.userInfo as? [String: Any])?["path"] as? String
  }

  /// Updates a template's sections and assistant cell from resolved content.
  private func updateTemplate(_ template: CPListTemplate, with resolvedTrack: ResolvedTrack) {
    let sections = listItemFactory?.createSections(from: resolvedTrack) ?? []
    template.updateSections(sections)
    configureAssistantCell(on: template, from: resolvedTrack)
  }

  /// Configures the assistant cell ("Ask Siri to Play Audio") on a template
  /// based on the `carPlaySiriListButton` property of the resolved content.
  private func configureAssistantCell(on template: CPListTemplate, from resolvedTrack: ResolvedTrack) {
    guard let position = resolvedTrack.carPlaySiriListButton else {
      template.assistantCellConfiguration = nil
      return
    }
    let cellPosition: CPListItem.AssistantCellPosition = position == .top ? .top : .bottom
    template.assistantCellConfiguration = CPAssistantCellConfiguration(
      position: cellPosition,
      visibility: .always,
      assistantAction: .playMedia,
    )
  }

  // MARK: - Content Loading

  /// Resolves `path` and fills an empty (loading) template. Driven by
  /// `templateDidAppear` → `loadContentIfNeeded`, so it runs once the template is
  /// on screen — the timing where CarPlay actually applies updates (updates made
  /// earlier, e.g. straight after push, are dropped). Shows a centered empty
  /// state on empty/error/timeout.
  @MainActor
  private func loadContent(for path: String, into template: CPListTemplate) async {
    guard let audioBrowser else { return }

    // Single-flight: don't start a second load if one is already in flight for
    // this template (e.g. the user backed out and into a still-loading screen).
    let templateId = ObjectIdentifier(template)
    guard !loadingTemplates.contains(templateId) else { return }
    loadingTemplates.insert(templateId)
    defer { loadingTemplates.remove(templateId) }

    // Watchdog: if still loading (no rows) after the timeout, show an error.
    let timeout = resolveTimeout
    let watchdog = Task { @MainActor [weak self] in
      do { try await Task.sleep(for: timeout) } catch { return } // cancelled → abort
      guard let self, template.sections.isEmpty else { return }
      self.logger.error("loadContent: resolve timed out for \(path)")
      // A timeout is a navigation error (code .timeout) too, so it formats through
      // the app's formatNavigationError like every other browse failure.
      let timedOut = NavigationError(
        code: .timeout, message: "", statusCode: nil, statusCodeSuccess: nil,
      )
      await self.showNavigationErrorView(timedOut, path: path, on: template)
    }

    do {
      let resolved = try await audioBrowser.browserManager.resolve(path, useCache: true)
      watchdog.cancel()
      // A page carrying an assistant cell (carPlaySiriListButton) is meaningful
      // even with no rows — the "Ask Siri to Play" cell IS its content — so it
      // renders rather than falling through to the empty-content state.
      let hasAssistantCell = resolved.carPlaySiriListButton != nil
      if resolved.children?.isEmpty ?? true, !hasAssistantCell {
        // Empty is modeled as a navigation error (code .emptyContent) so it goes
        // through the same path-aware formatter as failures — letting an app give
        // an empty Favorites tab different copy than an empty search. ADR 0001.
        let empty = NavigationError(
          code: .emptyContent, message: "", statusCode: nil, statusCodeSuccess: nil,
        )
        await showNavigationErrorView(empty, path: path, on: template)
      } else {
        updateTemplate(template, with: resolved)
      }
    } catch {
      watchdog.cancel()
      logger.error("Failed to load content for \(path): \(error.localizedDescription)")
      await showNavigationErrorView(NavigationError.from(error), path: path, on: template)
    }
  }

  /// Renders a navigation error as the template's centered empty/error view,
  /// formatted via the app's `formatNavigationError` (or the built-in default):
  /// `title → view title`, `message → subtitle`. Used for both real failures and
  /// the empty-content case (ADR 0001).
  @MainActor
  private func showNavigationErrorView(
    _ navError: NavigationError,
    path: String,
    on template: CPListTemplate,
  ) async {
    let formatted = await formattedNavigationError(navError, path: path)
    showMessage(
      on: template,
      title: formatted.title,
      subtitle: formatted.message.flatMap { $0.isEmpty ? nil : $0 },
    )
  }

  /// Resolves a navigation error to its display form via the app's
  /// `formatNavigationError` callback, falling back to the default if unset/failed.
  @MainActor
  private func formattedNavigationError(
    _ navError: NavigationError,
    path: String,
  ) async -> FormattedNavigationError {
    let fallback = navError.defaultFormatted()
    guard let formatter = config.formatNavigationError else { return fallback }
    let params = FormatNavigationErrorParams(
      error: navError, defaultFormatted: fallback, path: path,
    )
    do {
      let custom = try await formatter(params).await()
      return custom ?? fallback
    } catch {
      return fallback
    }
  }

  // MARK: - Selection Handling

  private func handleItemSelection(track: Track, completion: @escaping () -> Void) {
    logger.info("Selected track: \(track.title)")

    guard let audioBrowser else {
      completion()
      return
    }

    // If this track is already loaded, resume playback and show Now Playing.
    if track.src != nil, isActiveTrack(identity: track.identity) {
      try? audioBrowser.play()
      nowPlayingManager.showNowPlaying()
      completion()
      return
    }

    guard let player = audioBrowser.getPlayer(), let trackSelector else {
      completion()
      return
    }

    // A playable track always lands on the Now Playing surface (selection
    // resolves to .play or .intercepted, never .browse) — push it now and stamp
    // the tapped track's metadata, instead of after selection resolves: queue
    // expansion and the media URL resolve can both hit the network, and waiting
    // animates in a blank Now Playing screen. The load pipeline re-publishes
    // the same fields and dedupes.
    if track.src != nil {
      player.loadNowPlayingMetadata(for: track)
      // Derive the album line's destination from the tapped track before the
      // push — CarPlay reads isAlbumArtistButtonEnabled at display time.
      nowPlayingManager.prepareAlbumArtistButton(for: track)
      nowPlayingManager.showNowPlaying()
    }

    // Release CarPlay immediately so the list never locks up. Apple's handler
    // guidance is to finish processing the tap promptly; for a browse we "finish"
    // by pushing the destination and filling it in (see navigateToPath), and for
    // playback the Now Playing surface owns its own loading state.
    completion()

    Task { [weak self] in
      let result = await trackSelector.select(track: track, player: player)
      guard let self else { return }
      switch result {
      case let .play(intent):
        self.executePlayback(intent, player: player)
        self.nowPlayingManager.showNowPlaying(popToFront: false)
      case .intercepted:
        self.nowPlayingManager.showNowPlaying(popToFront: false)
      case let .browse(path):
        await self.navigateToPath(path, title: track.title)
      case .none:
        break
      }
    }
  }

  private func executePlayback(_ intent: TrackSelector.PlaybackIntent, player: TrackPlayer) {
    switch intent {
    case let .skipTo(index):
      try? player.skipTo(index, playWhenReady: true)
    case let .setQueue(tracks, startIndex, sourcePath):
      player.setQueue(tracks, initialIndex: startIndex, playWhenReady: true, sourcePath: sourcePath)
    case let .loadTrack(track):
      player.load(track, playWhenReady: true)
    }
  }

  /// Pushes a browsable path's destination immediately as an empty, spinning list
  /// template — so the list the user tapped from is never blocked. The content is
  /// filled by `templateDidAppear` → `loadContentIfNeeded` → `loadContent`, which
  /// runs once the template is on screen (the timing CarPlay needs: updates made
  /// right after a push are dropped). Backing out and re-tapping retries.
  @MainActor
  private func navigateToPath(_ path: String, title: String) async {
    // The gate is resolved per-request: a gated path pushes the gate page
    // (with this request's chrome) instead of browsing into content; this also
    // blocks indirect entries like the Now Playing album line. An allowed path
    // browses normally even while a gate is active.
    if isGated, let audioBrowser {
      let outcome = await audioBrowser.gateDecision(
        for: NativeGateRequest(reason: .browse, path: path, search: nil),
      )
      if outcome.gated {
        audioBrowser.onGate(GateEvent(reason: .browse))
        if let top = interfaceController.topTemplate, getPath(from: top) == path { return }
        interfaceController.safePush(
          makeGateTemplate(gate: outcome.chrome, tab: nil), animated: true,
        )
        return
      }
    }
    // Avoid pushing a duplicate if the top template already shows this path.
    if let top = interfaceController.topTemplate, getPath(from: top) == path {
      return
    }
    // …and guard a rapid double-tap whose first push hasn't appeared yet (so the
    // check above can't see it). Cleared when the pushed template appears.
    guard !navigatingPaths.contains(path) else { return }
    navigatingPaths.insert(path)

    let template = makeLoadingTemplate(title: title, path: path)
    interfaceController.safePush(template, animated: true) { [weak self] pushed, error in
      guard !pushed else { return }
      // The push can fail (e.g. CarPlay's template stack depth limit). The
      // appear callback that normally clears the guard will never fire, so
      // clear it here — otherwise the destination stays unreachable until
      // CarPlay reconnects.
      self?.navigatingPaths.remove(path)
      if let error {
        self?.logger.error("pushTemplate failed for \(path): \(error.localizedDescription)")
      }
    }
  }

  /// Builds an empty list template for content that is still resolving.
  /// On iOS 18.4+ it shows the system spinner (`showsSpinnerWhileEmpty`). On older
  /// iOS it shows the app-localized `carPlayLoadingTitle` as the centered empty
  /// state when configured; otherwise it stays blank rather than ship a
  /// hardcoded, un-localized "Loading…". The empty view is set at creation —
  /// the timing CarPlay renders reliably (see `replaceWithMessage`).
  private func makeLoadingTemplate(title: String?, path: String?) -> CPListTemplate {
    let template = CPListTemplate(title: title, sections: [])
    if let path {
      template.userInfo = ["path": path] as [String: Any]
    }
    if let loadingTitle = config.carPlayLoadingTitle {
      // Subtitle slot on purpose: the title slot renders at CarPlay's large
      // empty-state size, which is too heavy for a transient loading hint.
      template.emptyViewSubtitleVariants = [loadingTitle]
    }
    if #available(iOS 18.4, *) {
      template.showsSpinnerWhileEmpty = true
    }
    return template
  }

  /// Shows a centered empty/error message on a loading template. A *pushed*
  /// template is replaced (see `replaceWithMessage`); a *tab root* can't be
  /// popped, so its empty view is set in place (best-effort — a tab renders its
  /// empty view when selected).
  private func showMessage(on template: CPListTemplate, title: String, subtitle: String?) {
    if isTabRoot(template) {
      if #available(iOS 18.4, *) {
        template.showsSpinnerWhileEmpty = false
      }
      template.emptyViewTitleVariants = [title.isEmpty ? "Couldn't load" : title]
      template.emptyViewSubtitleVariants = subtitle.map { [$0] } ?? []
      template.updateSections([])
    } else {
      replaceWithMessage(template, title: title, subtitle: subtitle)
    }
  }

  /// Whether `template` is a tab's root template (a child of the tab bar). Such
  /// templates can't be popped, so the replace-by-pop strategy doesn't apply.
  private func isTabRoot(_ template: CPTemplate) -> Bool {
    guard let tabBar = interfaceController.rootTemplate as? CPTabBarTemplate else { return false }
    return tabBar.templates.contains { $0 === template }
  }

  /// Replaces an on-screen loading template with a fresh list template showing a
  /// centered empty-state message (empty result, or an error).
  ///
  /// We *replace* rather than mutate because CarPlay reliably renders a list
  /// template's empty view only as its **initial** state at push time — changing
  /// `emptyViewTitleVariants` / `showsSpinnerWhileEmpty` on an already-pushed
  /// template is unreliable (it renders late, or not at all). The replacement
  /// carries no `path`, so `loadContentIfNeeded` won't lazy-reload it; backing
  /// out and re-tapping retries.
  private func replaceWithMessage(_ loadingTemplate: CPListTemplate, title: String, subtitle: String?) {
    // Only replace if the loading template is still the visible top template
    // (the user may have backed out while the resolve was in flight).
    guard interfaceController.topTemplate === loadingTemplate else { return }

    let message = CPListTemplate(title: loadingTemplate.title, sections: [])
    message.emptyViewTitleVariants = [title.isEmpty ? "Couldn't load" : title]
    message.emptyViewSubtitleVariants = subtitle.map { [$0] } ?? []

    interfaceController.safePop(animated: false) { [weak self] _, _ in
      self?.interfaceController.safePush(message, animated: false)
    }
  }

  // MARK: - Content Change Handlers

  @MainActor
  private func handleTabsChanged(_ tabs: [Track]) {
    logger.debug("Tabs changed: \(tabs.count) tabs")
    // Same tabs by path while ungated → only the tab-bar entries changed
    // (e.g. titles after a locale switch): restamp them in place. No rebuild,
    // so the selected tab and any pushed navigation stack survive. (Gate
    // templates carry no path, so a gated tab bar never matches here.)
    if !isGated,
       let tabBar = interfaceController.rootTemplate as? CPTabBarTemplate,
       tabBar.templates.count == min(tabs.count, CPTabBarTemplate.maximumTabCount),
       zip(tabBar.templates, tabs).allSatisfy({ getPath(from: $0) == $1.path })
    {
      for (template, tab) in zip(tabBar.templates, tabs) {
        applyTabBarEntry(to: template, for: tab)
      }
      pendingTabs = nil
      return
    }
    // Rebuilding replaces the root template, tearing down any pushed
    // navigation stack. If the user is browsing, defer until they're back at
    // the tab bar (applied from templateDidAppear).
    guard interfaceController.templates.count <= 1 else {
      pendingTabs = tabs
      return
    }
    pendingTabs = nil
    Task {
      await showTabBar(tabs: tabs)
    }
  }

  /// Applies a deferred tab change once the user has popped back to the tab
  /// bar. Returns true if a rebuild was kicked off (the appeared template is
  /// about to be replaced, so callers should skip further work on it).
  @MainActor
  fileprivate func applyPendingTabsIfAtRoot() -> Bool {
    guard let tabs = pendingTabs, interfaceController.templates.count <= 1 else {
      return false
    }
    pendingTabs = nil
    Task {
      await showTabBar(tabs: tabs)
    }
    return true
  }

  @MainActor
  private func handleContentChanged(_ content: ResolvedTrack?) {
    // This callback fires when the main browser's content changes.
    // For CarPlay-specific refreshes (e.g., favorites), use notifyContentChanged instead.
    guard let content else { return }
    refreshTemplatesForPath(content.path, with: content)
  }

  @MainActor
  private func handleActiveTrackChanged(_ event: PlaybackActiveTrackChangedEvent) {
    logger.debug("handleActiveTrackChanged: \(event.lastTrack?.src ?? "nil") → \(event.track?.src ?? "nil")")
    updatePlayingIndicators()
    // Refresh per-track button state: favorite heart, Up Next availability,
    // and the album line's pre-resolved destination.
    nowPlayingManager.handleActiveTrackChanged()
  }

  /// Updates the isPlaying state on all list items based on the current active track.
  @MainActor
  fileprivate func updatePlayingIndicators() {
    var templates: [CPListTemplate] = []

    if let tabBar = interfaceController.rootTemplate as? CPTabBarTemplate {
      for template in tabBar.templates {
        if let listTemplate = template as? CPListTemplate {
          templates.append(listTemplate)
        }
      }
    }

    if let topTemplate = interfaceController.topTemplate as? CPListTemplate,
       !templates.contains(where: { $0 === topTemplate })
    {
      templates.append(topTemplate)
    }

    for template in templates {
      var templateChanged = false
      for section in template.sections {
        for item in section.items {
          guard let listItem = item as? CPListItem,
                let info = listItem.carPlayItemInfo,
                info.identity != nil
          else { continue }

          let isPlaying = isActiveTrack(identity: info.identity)
          if listItem.isPlaying != isPlaying {
            logger.debug(
              "Updating isPlaying for \(info.identity ?? ""): \(listItem.isPlaying) → \(isPlaying)")
            listItem.isPlaying = isPlaying
            templateChanged = true
          }
        }
      }
      // Setting isPlaying on an already-displayed item does not reliably reach
      // the CarPlay screen (rdar-known; the flag only renders when the item is
      // pushed with it already set). Re-pushing the same sections re-serializes
      // every item with its current flags — done only when something actually
      // changed, so routine appear-time passes don't churn the display.
      if templateChanged {
        template.updateSections(template.sections)
      }
    }
  }

  // MARK: - Public Content Notification

  /// Notifies CarPlay that content at the given path has changed and should be refreshed.
  /// Called from HybridAudioBrowser.notifyContentChanged() to update CarPlay lists.
  ///
  /// - Parameter path: The path where content has changed (e.g., "/favorites")
  @objc
  public func notifyContentChanged(path: String) {
    guard isStarted else { return }

    Task { @MainActor in
      await refreshContentForPath(path)
    }
  }

  /// Fetches fresh content for a path and updates any matching CarPlay templates.
  @MainActor
  private func refreshContentForPath(_ path: String) async {
    guard let audioBrowser else { return }

    logger.debug("Refreshing CarPlay content for path: \(path)")

    // Fetch fresh content (bypassing cache since content changed)
    do {
      let resolved = try await audioBrowser.browserManager.resolve(path, useCache: false)
      refreshTemplatesForPath(path, with: resolved)
    } catch {
      logger.error("Failed to refresh content for \(path): \(error.localizedDescription)")
    }
  }

  /// Updates all CarPlay templates that are displaying the given path.
  @MainActor
  private func refreshTemplatesForPath(_ path: String, with content: ResolvedTrack) {
    logger.debug("Content changed for path: \(path)")

    // Check if the root template is a tab bar and refresh matching tabs
    if let tabBar = interfaceController.rootTemplate as? CPTabBarTemplate {
      for template in tabBar.templates {
        guard let listTemplate = template as? CPListTemplate,
              let templatePath = getPath(from: listTemplate),
              templatePath == path
        else { continue }

        logger.info("Refreshing tab template for path: \(path)")
        updateTemplate(listTemplate, with: content)
      }
    }

    // Check if any template in the navigation stack matches the changed path
    // The top template is the currently visible one
    if let topTemplate = interfaceController.topTemplate as? CPListTemplate,
       let templatePath = getPath(from: topTemplate),
       templatePath == path
    {
      logger.info("Refreshing top template for path: \(path)")
      updateTemplate(topTemplate, with: content)
    }
  }

  /// Refreshes every currently-displayed template (tabs + navigation stack) by
  /// re-fetching each path with the cache bypassed. Called when all content was
  /// invalidated app-wide (e.g. a locale change).
  @MainActor
  public func invalidateAllContent() {
    guard isStarted else { return }
    Task { @MainActor in await refreshAllDisplayedTemplates() }
  }

  @MainActor
  private func refreshAllDisplayedTemplates() async {
    guard let audioBrowser else { return }

    var templates: [CPListTemplate] = []
    if let tabBar = interfaceController.rootTemplate as? CPTabBarTemplate {
      for template in tabBar.templates {
        if let listTemplate = template as? CPListTemplate {
          templates.append(listTemplate)
        }
      }
    }
    for template in interfaceController.templates {
      if let listTemplate = template as? CPListTemplate,
         !templates.contains(where: { $0 === listTemplate })
      {
        templates.append(listTemplate)
      }
    }

    for template in templates {
      guard let path = getPath(from: template) else { continue }
      do {
        let resolved = try await audioBrowser.browserManager.resolve(path, useCache: false)
        updateTemplate(template, with: resolved)
      } catch {
        logger.error("invalidateAllContent: failed to refresh \(path): \(error.localizedDescription)")
      }
    }
  }

  // MARK: - Gate

  /// Applies a gate state change: set or clear. The per-request chrome is
  /// obtained at render time via `gateDecision(for:)`, so this just tears down
  /// any pushed navigation and rebuilds the tab bar — `showTabBar` routes to the
  /// gated build while a gate is active and to the normal build once cleared.
  /// Custom Now Playing buttons (e.g. favorite) hide while gated and return on
  /// clear.
  @MainActor
  private func handleGateChanged(_ active: Bool) {
    isGated = active
    let tabs = audioBrowser?.browserManager.getTabs() ?? []
    if tabs.isEmpty, !active {
      // Never had tabs (gate was up since before config) — full initial build.
      Task { await buildInitialInterface() }
    } else {
      // Tear down any pushed navigation — gated content must not stay reachable
      // behind the gate, and on clear we return to the root. (No-op at root.)
      interfaceController.safePopToRoot(animated: false)
      Task { await showTabBar(tabs: tabs) }
    }
    nowPlayingManager.setupNowPlayingButtons()
    nowPlayingManager.updateNowPlayingButtonStates()
  }

  /// Builds the gate page for one tab-bar slot, carrying the tab's entry so
  /// the tab bar itself stays familiar.
  ///
  /// A CPListTemplate, NOT a CPInformationTemplate: CarPlay enforces
  /// per-entitlement template allowances at runtime, and the audio
  /// entitlement does not include the information template — handing one to
  /// CPTabBarTemplate throws an unhandled ObjC exception (crash at init).
  ///
  /// Within a list, tab children never show navigation-bar buttons (the tab
  /// bar owns that chrome), and the centered empty view renders only when the
  /// list has NO rows. The gate is a plain centered message — `title` as the
  /// empty-view title and `message` as its subtitle, where newlines collapse
  /// to spaces (the "variants" are width alternatives, not lines). No action
  /// button: a gate withholds content; the consumer surfaces any "subscribe"
  /// affordance in its own UI, not on the car surface.
  private func makeGateTemplate(gate gateChrome: Gate?, tab: Track?) -> CPListTemplate {
    // A gated decision with neither an override nor a stored default chrome
    // (resolver-only `true`) falls back to the built-in minimal gate.
    let gate = gateChrome ?? HybridAudioBrowser.builtInGate
    let template = CPListTemplate(title: gate.title, sections: [])
    // Set at creation — the timing CarPlay renders the empty view reliably
    // (see replaceWithMessage).
    template.emptyViewTitleVariants = [gate.title]
    if let message = gate.message, !message.isEmpty {
      template.emptyViewSubtitleVariants = [
        message.replacingOccurrences(of: "\n", with: " "),
      ]
    }
    // Marks the page as a gate (vs. a content tab, which carries a `path`),
    // so the lazy-loader and refresh paths never try to fill it.
    template.userInfo = ["gate": true] as [String: Any]
    if let tab {
      applyTabBarEntry(to: template, for: tab)
    }
    return template
  }

  // MARK: - Error Handling

  /// Shows a navigation error using CPActionSheetTemplate.
  /// - Parameters:
  ///   - error: The NavigationError to display
  ///   - path: The path that was being navigated to when the error occurred
  private func showNavigationError(_ error: NavigationError, path: String) {
    let defaultFormatted = error.defaultFormatted()

    // Check if custom formatter is configured
    logger.debug("showNavigationError: formatNavigationError is \(self.config.formatNavigationError != nil ? "set" : "nil")")
    if let formatter = config.formatNavigationError {
      logger.debug("Calling formatNavigationError callback...")
      // Call the JS callback and handle result
      let params = FormatNavigationErrorParams(error: error, defaultFormatted: defaultFormatted, path: path)
      formatter(params)
        .then { [weak self] customDisplay in
          self?.logger.debug("formatNavigationError returned: \(String(describing: customDisplay))")
          self?.presentErrorActionSheet(customDisplay: customDisplay ?? defaultFormatted)
        }
        .catch { [weak self] callbackError in
          self?.logger.error("formatNavigationError failed: \(callbackError)")
          // On error, fall back to defaults
          self?.presentErrorActionSheet(customDisplay: defaultFormatted)
        }
    } else {
      presentErrorActionSheet(customDisplay: defaultFormatted)
    }
  }

  /// Presents the error action sheet with the given display info.
  /// - Parameter customDisplay: The formatted error to display
  private func presentErrorActionSheet(customDisplay: FormattedNavigationError) {
    // If another template is already presented, dismiss it first
    if interfaceController.presentedTemplate != nil {
      interfaceController.safeDismiss(animated: false) { [weak self] _, _ in
        self?.showErrorActionSheet(customDisplay: customDisplay)
      }
    } else {
      showErrorActionSheet(customDisplay: customDisplay)
    }
  }

  /// Actually shows the error action sheet (called after safety checks)
  private func showErrorActionSheet(customDisplay: FormattedNavigationError) {
    // OK action - dismiss the action sheet (use system-localized "OK")
    let okTitle = Bundle(for: UIAlertController.self).localizedString(forKey: "OK", value: "OK", table: nil)
    let ok = CPAlertAction(title: okTitle, style: .cancel) { [weak self] _ in
      self?.interfaceController.safeDismiss(animated: true)
    }

    let actionSheet = CPActionSheetTemplate(
      title: customDisplay.title,
      message: customDisplay.message,
      actions: [ok],
    )

    interfaceController.safePresent(actionSheet, animated: true)
  }

  /// Shows an initialization failure as the root template — a centered empty
  /// view formatted via the app's `formatNavigationError` (path "/"), like every
  /// other browse failure. The empty view renders reliably here because it's the
  /// template's initial state at set-root time.
  @MainActor
  private func showRootNavigationError(_ navError: NavigationError) async {
    let formatted = await formattedNavigationError(navError, path: "/")
    let template = CPListTemplate(title: nil, sections: [])
    template.emptyViewTitleVariants = [formatted.title]
    template.emptyViewSubtitleVariants = formatted.message.flatMap { $0.isEmpty ? nil : [$0] } ?? []
    interfaceController.safeSetRoot(template, animated: true)
  }
}

// MARK: - CPInterfaceControllerDelegate

/// Separate delegate class to avoid exposing CPInterfaceControllerDelegate to Obj-C header
private final class InterfaceControllerDelegate: NSObject, CPInterfaceControllerDelegate {
  private weak var controller: RNABCarPlayController?

  init(controller: RNABCarPlayController) {
    self.controller = controller
    super.init()
  }

  func templateDidAppear(_ aTemplate: CPTemplate, animated _: Bool) {
    // Popping back from Now Playing surfaces the tab bar (not a list), so the
    // guard must let both through — the playing indicator was toggled while
    // the list was covered, and this appearance is the moment to repaint it.
    guard aTemplate is CPListTemplate || aTemplate is CPTabBarTemplate else { return }

    // A tab change deferred while the user was browsing replaces the whole
    // tab bar — no point updating a template that's about to be discarded.
    if controller?.applyPendingTabsIfAtRoot() == true { return }

    // Update playing indicators when navigating back to a list template
    controller?.updatePlayingIndicators()

    // Lazy load content for tabs that haven't been loaded yet
    if let listTemplate = aTemplate as? CPListTemplate {
      controller?.loadContentIfNeeded(for: listTemplate)
    }
  }

  func templateDidDisappear(_ aTemplate: CPTemplate, animated _: Bool) {
    // The complement of the appear hook: whether popping Now Playing reports
    // "tab bar appeared" or only "now playing disappeared" is a platform
    // detail — cover both so the revealed list always repaints its indicator.
    guard aTemplate is CPNowPlayingTemplate else { return }
    controller?.updatePlayingIndicators()
  }
}

// MARK: - Lazy Loading

private extension RNABCarPlayController {
  /// Loads content for a template if it hasn't been loaded yet (lazy loading for tabs)
  func loadContentIfNeeded(for template: CPListTemplate) {
    // Get path from userInfo
    guard let path = getPath(from: template) else { return }

    // The template is now on screen, so a queued duplicate navigation to it is
    // no longer a concern (the top-template check will catch any further taps).
    navigatingPaths.remove(path)

    // Skip if already has content (single-flight in loadContent guards re-entry).
    guard template.sections.isEmpty else { return }

    logger.debug("Lazy loading content for tab: \(path)")

    Task {
      await loadContent(for: path, into: template)
    }
  }
}

// MARK: - UIImage Resize

extension UIImage {
  /// Draws the image centered within the target size, maintaining aspect
  /// ratio, at the given display scale (the car screen's, not the phone's).
  func resized(to targetSize: CGSize, scale: CGFloat) -> UIImage? {
    UIGraphicsBeginImageContextWithOptions(targetSize, false, scale)
    defer { UIGraphicsEndImageContext() }

    // Scale to fit while maintaining aspect ratio
    let widthRatio = targetSize.width / size.width
    let heightRatio = targetSize.height / size.height
    let scale = min(widthRatio, heightRatio)

    let scaledSize = CGSize(width: size.width * scale, height: size.height * scale)
    let origin = CGPoint(
      x: (targetSize.width - scaledSize.width) / 2,
      y: (targetSize.height - scaledSize.height) / 2,
    )

    draw(in: CGRect(origin: origin, size: scaledSize))
    return UIGraphicsGetImageFromCurrentImageContext()?.withRenderingMode(.alwaysTemplate)
  }
}
