import CarPlay
import NitroModules
import os.log

/// One CarPlay run, from the moment the player and a configured browser exist until the scene
/// stops or the bridge is replaced. `end()` cancels its subscriptions and tasks, so nothing a
/// stopped run started paints over the next one.
@MainActor
final class CarPlaySession {
  let logger = Logger(subsystem: "com.audiobrowser", category: "CarPlaySession")

  let interfaceController: CPInterfaceController
  let audioBrowser: HybridAudioBrowser
  /// Read on each use: a re-setup replaces the bridge's player.
  var player: Player? { audioBrowser.player }
  let trackSelector: TrackSelector
  let imageLoader: CarPlayImageLoader
  private(set) lazy var listItemFactory = CarPlayListItemFactory(
    imageLoader: imageLoader,
    playingIndicatorLocation: playingIndicatorLocation,
    isActiveTrack: { [weak self] identity in identity != nil && self?.player?.currentTrack?.identity == identity },
    spawn: { [weak self] work in self?.spawn { _ in await work() } },
    onItemSelected: { [weak self] track, completion in self?.select(track, completion: completion) },
    onNavigate: { [weak self] path, title in self?.spawn { await $0.navigate(to: path, title: title) } },
  )
  private(set) var nowPlaying: CarPlayNowPlayingManager!

  private var subscriptions: [Subscription] = []
  private var tasks: [UUID: Task<Void, Never>] = [:]
  private(set) var ended = false

  /// Paths pushed but not yet appeared; `topTemplate` shows a push only once it lands, so this
  /// catches a double tap.
  var navigatingPaths: Set<String> = []

  /// Templates with a load in flight, so a re-appearing one does not start another.
  var loadingTemplates: Set<ObjectIdentifier> = []

  /// Rebuilding the tab bar tears down the pushed stack, so a tab change waits for the root.
  var needsTabRebuild = false

  /// The tabs the current root was built from; their change event, which arrives after the
  /// build began, has nothing left to change.
  var builtTabs: [Track]?

  /// Bumped by every root build; a suspended build that finds it bumped leaves the root alone.
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
    nowPlaying = CarPlayNowPlayingManager(
      interfaceController: interfaceController,
      audioBrowser: audioBrowser,
      listItem: { [weak self] track, handler in
        self?.listItemFactory.createListItem(for: track, handler: handler) ?? CPListItem(text: track.title, detailText: nil)
      },
      navigate: { [weak self] path, title in self?.spawn { await $0.navigate(to: path, title: title) } },
    )
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
    // Popping Now Playing reports the tab bar appearing, not the list; both repaint the
    // indicator, which may have changed while covered.
    guard template is CPListTemplate || template is CPTabBarTemplate else { return }
    if let path = template.carPlayInfo?.path { navigatingPaths.remove(path) }
    if rebuildTabsIfAtRoot() { return }
    updatePlayingIndicators()
    if let list = template as? CPListTemplate { loadContentIfNeeded(for: list) }
  }

  func templateDidDisappear(_ template: CPTemplate) {
    // Whether popping Now Playing reports the list appearing or only Now Playing leaving is a
    // platform detail; both repaint.
    guard template is CPNowPlayingTemplate else { return }
    updatePlayingIndicators()
  }

  // MARK: - Playing Indicators

  var playingIndicatorLocation: CPListItemPlayingIndicatorLocation {
    switch audioBrowser.playerOptions.carPlayPlayingIndicatorLocation {
    case .trailing: .trailing
    case .leading: .leading
    }
  }

  private func playerOptionsChanged() {
    let location = playingIndicatorLocation
    guard listItemFactory.playingIndicatorLocation != location else { return }
    listItemFactory.playingIndicatorLocation = location
    updatePlayingIndicators()
  }

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
      // sections re-serializes every item, so it is done only on a change.
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
