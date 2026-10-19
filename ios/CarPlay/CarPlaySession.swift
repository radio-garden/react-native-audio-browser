import CarPlay
import NitroModules
import os.log

/// One CarPlay run, from the moment the player and a configured browser exist until the scene
/// stops or the bridge is replaced. It owns its subscriptions and tasks: `end()` cancels them,
/// so nothing a stopped run started can paint over the next one.
@MainActor
final class CarPlaySession {
  let logger = Logger(subsystem: "com.audiobrowser", category: "CarPlaySession")

  let interfaceController: CPInterfaceController
  let audioBrowser: HybridAudioBrowser
  /// Read on each use: a re-setup replaces the bridge's player.
  var player: Player? { audioBrowser.getPlayer() }
  let trackSelector: TrackSelector
  let imageLoader: CarPlayImageLoader
  let listItemFactory: CarPlayListItemFactory
  private(set) var nowPlaying: CarPlayNowPlayingManager!

  private var subscriptions: [Subscription] = []
  private var tasks: [UUID: Task<Void, Never>] = [:]
  private var ended = false

  /// Paths whose pushed template has not appeared yet; guards a double tap, since
  /// `topTemplate` only shows a push once it lands.
  var navigatingPaths: Set<String> = []

  /// Templates with a content load in flight, so a re-appearing one doesn't start another.
  var loadingTemplates: Set<ObjectIdentifier> = []

  /// Set when the tabs change while the user is browsing: rebuilding the tab bar would tear
  /// down the pushed stack, so it waits until they are back at the root.
  var needsTabRebuild = false

  /// The tabs the current root was built from; their change event, which the browser delivers
  /// after the build has begun, has nothing left to change.
  var builtTabs: [Track]?

  /// Bumped by every root build (initial interface, tab bar, root error). A build that
  /// suspended and finds it bumped has been superseded, and leaves the root alone.
  private var rootBuildGeneration: UInt = 0

  /// How long a destination may spin before it shows an error.
  let resolveTimeout: Duration = .seconds(15)

  var config: BrowserConfig {
    audioBrowser.browserManager.config
  }

  init(interfaceController: CPInterfaceController, audioBrowser: HybridAudioBrowser) {
    self.interfaceController = interfaceController
    self.audioBrowser = audioBrowser
    trackSelector = TrackSelector(browserManager: audioBrowser.browserManager)
    imageLoader = CarPlayImageLoader(
      carTraitCollection: interfaceController.carTraitCollection,
      browserManager: audioBrowser.browserManager,
    )
    listItemFactory = CarPlayListItemFactory(
      isActiveTrack: { [weak audioBrowser] identity in
        identity != nil && audioBrowser?.getPlayer()?.currentTrack?.identity == identity
      },
    )
    listItemFactory.imageLoader = imageLoader
    nowPlaying = CarPlayNowPlayingManager(
      interfaceController: interfaceController,
      audioBrowser: audioBrowser,
      listItem: { [weak self] track, handler in
        self?.listItemFactory.createListItem(for: track, handler: handler) ?? CPListItem(text: track.title, detailText: nil)
      },
      navigate: { [weak self] path, title in self?.spawn { await $0.navigate(to: path, title: title) } },
    )
    listItemFactory.onItemSelected = { [weak self] track, completion in self?.select(track, completion: completion) }
    listItemFactory.onNavigate = { [weak self] path, title in self?.spawn { await $0.navigate(to: path, title: title) } }
    listItemFactory.playingIndicatorLocation = playingIndicatorLocation
  }

  func start() {
    let browserManager = audioBrowser.browserManager
    subscriptions = [
      browserManager.tabsChanged.subscribeOnMain { [weak self] in self?.tabsChanged($0) },
      browserManager.contentChanged.subscribeOnMain { [weak self] content in
        guard let content else { return }
        self?.refreshDisplayedTemplates(for: content.path, with: content)
      },
      audioBrowser.playerOptionsChangedEmitter.subscribeOnMain { [weak self] in self?.playerOptionsChanged() },
      audioBrowser.externalContentChangedEmitter.subscribeOnMain { [weak self] path in
        guard let self else { return }
        if path == HybridAudioBrowser.invalidateAllSentinel {
          spawn { await $0.refreshAllDisplayedTemplates() }
        } else {
          spawn { await $0.refreshContent(for: path) }
        }
      },
      audioBrowser.browseGate.changed.subscribeOnMain { [weak self] _ in self?.gateChanged() },
      audioBrowser.activeTrackChangedEmitter.subscribeOnMain { [weak self] _ in self?.updatePlayingIndicators() },
    ]
    nowPlaying.start()
    spawn { await $0.buildInitialInterface() }
  }

  func end() {
    ended = true
    for task in tasks.values {
      task.cancel()
    }
    tasks = [:]
    subscriptions = []
    nowPlaying.end()
  }

  /// Runs `work` as a task this session owns, cancelled by `end()`.
  func spawn(_ work: @escaping @MainActor (CarPlaySession) async -> Void) {
    // A handler queued before `end()` can still call in; the next run owns the screen.
    guard !ended else { return }
    let id = UUID()
    tasks[id] = Task { [weak self] in
      guard let self else { return }
      await work(self)
      tasks[id] = nil
    }
  }

  // MARK: - Root Builds

  /// Starts a root build; pass the result to `isCurrent(_:)` after every suspension.
  func beginRootBuild() -> UInt {
    builtTabs = nil
    rootBuildGeneration &+= 1
    return rootBuildGeneration
  }

  func isCurrent(_ build: UInt) -> Bool {
    build == rootBuildGeneration && !Task.isCancelled
  }

  // MARK: - Template Events

  func templateDidAppear(_ template: CPTemplate) {
    // Popping back from Now Playing reveals the tab bar, not a list; both repaint the playing
    // indicator, which may have changed while the list was covered.
    guard template is CPListTemplate || template is CPTabBarTemplate else { return }
    if let path = template.carPlayInfo?.path { navigatingPaths.remove(path) }
    if rebuildTabsIfAtRoot() { return }
    updatePlayingIndicators()
    if let list = template as? CPListTemplate { loadContentIfNeeded(for: list) }
  }

  func templateDidDisappear(_ template: CPTemplate) {
    // Whether popping Now Playing reports the list appearing or only Now Playing leaving is a
    // platform detail; both repaint the indicator.
    guard template is CPNowPlayingTemplate else { return }
    updatePlayingIndicators()
  }

  // MARK: - Playing Indicators

  var playingIndicatorLocation: CPListItemPlayingIndicatorLocation {
    switch audioBrowser.carPlayPlayingIndicatorLocation {
    case .trailing: .trailing
    case .leading: .leading
    }
  }

  private func playerOptionsChanged() {
    // The factory's copy is the last-applied location: sweep only on a real change.
    let location = playingIndicatorLocation
    guard listItemFactory.playingIndicatorLocation != location else { return }
    listItemFactory.playingIndicatorLocation = location
    updatePlayingIndicators()
  }

  /// Updates every displayed list item's playing flag and indicator location.
  func updatePlayingIndicators() {
    let location = playingIndicatorLocation
    for template in displayedListTemplates() {
      var changed = false
      for section in template.sections {
        for case let item as CPListItem in section.items {
          guard let identity = item.carPlayItemInfo?.identity else { continue }
          let isPlaying = player?.currentTrack?.identity == identity
          if item.isPlaying != isPlaying {
            item.isPlaying = isPlaying
            changed = true
          }
          if item.playingIndicatorLocation != location {
            item.playingIndicatorLocation = location
            changed = true
          }
        }
      }
      // A flag set on a displayed item does not reliably reach the car screen; re-pushing the
      // sections re-serializes every item, so it is done only when something changed.
      if changed { template.updateSections(template.sections) }
    }
  }

  /// The list templates on screen or one tap away: the tab roots, then the pushed stack.
  func displayedListTemplates() -> [CPListTemplate] {
    var templates: [CPListTemplate] = []
    let tabRoots = (interfaceController.rootTemplate as? CPTabBarTemplate)?.templates ?? []
    for case let list as CPListTemplate in tabRoots + interfaceController.templates
      where !templates.contains(where: { $0 === list })
    {
      templates.append(list)
    }
    return templates
  }
}
