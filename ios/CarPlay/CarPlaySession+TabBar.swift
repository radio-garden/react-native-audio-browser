import CarPlay
import NitroModules

extension CarPlaySession {
  /// Drops disabled tabs: `CPTabBarTemplate` has no disabled state (Android Auto filters the
  /// same way).
  static func visibleTabs(_ tabs: [Track]) -> [Track] {
    tabs.filter { $0.disabled != true }
  }

  func buildInitialInterface() async {
    let build = beginRootBuild()
    if let tabs = audioBrowser.browserManager.tabs, !tabs.isEmpty {
      await showTabBar(tabs)
      return
    }
    // The first loading root predates the config; re-show it with the app's loading title.
    if let loadingTitle = config.carPlayLoadingTitle {
      interfaceController.safeSetRoot(CPListTemplate.loading(title: nil, path: nil, loadingTitle: loadingTitle), animated: false)
    }
    do {
      let tabs = try await audioBrowser.browserManager.queryTabs()
      guard isCurrent(build) else { return }
      await showTabBar(tabs)
    } catch {
      logger.error("Failed to query tabs: \(error.localizedDescription)")
      await showRootError(NavigationError.from(error), build: build)
    }
  }

  /// Builds the tab bar. While the Browse Gate is active each tab is decided on its own: a
  /// gated tab becomes its gate page, an allowed one shows content.
  func showTabBar(_ allTabs: [Track]) async {
    let build = beginRootBuild()
    builtTabs = allTabs
    let tabs = Array(Self.visibleTabs(allTabs).prefix(CPTabBarTemplate.maximumTabCount))
    let gated = audioBrowser.browseGate.isActive

    // An empty tab bar renders blank: show the gate's page, or else the empty-content error
    // (ADR 0001).
    guard !tabs.isEmpty else {
      if gated, let chrome = await browseGates(for: [nil])[0] {
        guard isCurrent(build) else { return }
        reportGates([chrome], reason: .browse)
        interfaceController.safeSetRoot(makeGatePage(chrome, path: nil, tab: nil), animated: true)
      } else {
        await showRootError(.code(.emptyContent), build: build)
      }
      return
    }

    let chromes = gated ? await browseGates(for: tabs.map(\.path)) : tabs.map { _ in nil }
    guard isCurrent(build) else { return }
    reportGates(chromes, reason: .browse)
    let templates = zip(tabs, chromes).map { tab, chrome in
      chrome.map { makeGatePage($0, path: tab.path, tab: tab) } ?? makeTabTemplate(for: tab)
    }

    if gated, let tabBar = interfaceController.rootTemplate as? CPTabBarTemplate,
       tabBar.templates.count == templates.count
    {
      // An equal-count swap keeps the selected tab.
      tabBar.updateTemplates(templates)
    } else {
      let tabBar = CPTabBarTemplate(templates: templates)
      tabBar.delegate = tabBarDelegate
      interfaceController.safeSetRoot(tabBar, animated: true)
    }

    // Tabs load on first appearance. The first is already on screen, and a template swapped in
    // by `updateTemplates` may never report appearing.
    let eager = gated ? templates : Array(templates.prefix(1))
    for template in eager {
      guard let path = template.contentPath else { continue }
      await loadContent(for: path, into: template)
    }
  }

  /// Restamps the tab entries in place when the paths are unchanged; otherwise rebuilds the tab
  /// bar, deferred until the user is back at the root.
  func tabsChanged(_ tabs: [Track]) {
    guard !TabBarEntries.same(builtTabs, tabs) else { return }
    let visible = Self.visibleTabs(tabs)
    if !audioBrowser.browseGate.isActive,
       let tabBar = interfaceController.rootTemplate as? CPTabBarTemplate,
       tabBar.templates.count == min(visible.count, CPTabBarTemplate.maximumTabCount),
       zip(tabBar.templates, visible).allSatisfy({ $0.contentPath == $1.path })
    {
      for (template, tab) in zip(tabBar.templates, visible) {
        applyTabBarEntry(to: template, for: tab)
      }
      redraw(tabBar)
      builtTabs = tabs
      needsTabRebuild = false
      return
    }
    needsTabRebuild = true
    _ = rebuildTabsIfAtRoot()
  }

  /// Rebuilds the tab bar if a rebuild is due and the user is at the root. True when it did, so
  /// the caller can skip work on a template about to be replaced.
  func rebuildTabsIfAtRoot() -> Bool {
    guard needsTabRebuild, interfaceController.templates.count <= 1 else { return false }
    needsTabRebuild = false
    spawn { await $0.showTabBar($0.audioBrowser.browserManager.tabs ?? []) }
    return true
  }

  /// Rebuilds the root, popping pushed content so none stays reachable behind a new gate.
  func gateChanged() {
    let tabs = audioBrowser.browserManager.tabs ?? []
    if tabs.isEmpty, !audioBrowser.browseGate.isActive {
      spawn { await $0.buildInitialInterface() }
    } else {
      interfaceController.safePopToRoot(animated: false)
      spawn { await $0.showTabBar(tabs) }
    }
    nowPlaying.refresh()
  }

  /// The chrome to show if the Browse Gate gates this request, reported to JS; nil when allowed.
  func gate(_ reason: GateReason, path: String?, search: SearchParams? = nil) async -> Gate? {
    await audioBrowser.browseGate.decide(NativeGateRequest(reason: reason, path: path, search: search)) {
      audioBrowser.onGate($0)
    }
  }

  /// One decision per path, made concurrently, in path order. Reports nothing: the caller
  /// reports once it knows its build is still current.
  private func browseGates(for paths: [String?]) async -> [Gate?] {
    let browseGate = audioBrowser.browseGate
    return await paths.concurrentMap { path in
      await browseGate.decide(NativeGateRequest(reason: .browse, path: path, search: nil))
    }
  }

  private func reportGates(_ chromes: [Gate?], reason: GateReason) {
    for _ in chromes.compactMap(\.self) {
      audioBrowser.onGate(GateEvent(reason: reason))
    }
  }

  private func makeTabTemplate(for tab: Track) -> CPListTemplate {
    let template = CPListTemplate.loading(title: tab.title, path: tab.path, loadingTitle: config.carPlayLoadingTitle)
    applyTabBarEntry(to: template, for: tab)
    return template
  }

  /// A list template, as the audio entitlement does not allow `CPInformationTemplate` (the tab
  /// bar raises on one). No action button: the app offers any "subscribe" in its own UI.
  func makeGatePage(_ chrome: Gate, path: String?, tab: Track?) -> CPListTemplate {
    let template = CPListTemplate.message(
      chrome.title, subtitle: chrome.message, listTitle: chrome.title,
      info: CarPlayTemplateInfo(path: path, kind: .gate),
    )
    if let tab { applyTabBarEntry(to: template, for: tab) }
    return template
  }

  func applyTabBarEntry(to template: CPTemplate, for track: Track) {
    template.tabTitle = track.title
    tabImageLoads.removeObject(forKey: template)
    switch TabBarEntries.image(for: track) {
    case let .sfSymbol(artwork):
      // CarPlay tints the tab image, so the artwork's colours are dropped.
      template.tabImage = UIImage(systemName: SFSymbolRenderer.parseArtwork(artwork).symbolName)
    case let .asset(name):
      let image = UIImage(named: name)
      if image == nil { logger.debug("No image named \(name) in the asset catalog for tab \(track.title)") }
      template.tabImage = image?.withRenderingMode(.alwaysTemplate)
    case .fetch:
      loadTabImage(into: template, for: track)
    case .none:
      // A restamp must not leave an old icon on a now title-only tab.
      template.tabImage = nil
    }
  }

  /// One load per template: a restamp drops the one before, so a slow fetch cannot replace the
  /// newer image.
  private func loadTabImage(into template: CPTemplate, for track: Track) {
    // A handler queued before `end()` can still call in; the next run owns the screen.
    guard !ended else { return }
    let load = SupersedingTask<Never, UIImage>()
    tabImageLoads.setObject(load, forKey: template)
    load.run(key: nil) { [imageLoader] in
      // Tab icons are 24 pt (CarPlay Developer Guide).
      await imageLoader.artwork(for: track, size: CGSize(width: 24, height: 24))
    } deliver: { [weak self, weak template] result in
      guard let self, let template, case let .success(image?) = result else { return }
      template.tabImage = image
      if let tabBar = interfaceController.rootTemplate as? CPTabBarTemplate, tabBar.templates.contains(template) {
        redraw(tabBar)
      }
    }
  }

  /// A tab title or image changed after the tab bar is root may not be drawn until its templates
  /// are set again.
  private func redraw(_ tabBar: CPTabBarTemplate) {
    tabBar.updateTemplates(tabBar.templates)
  }

  /// Shows the app's formatting of `error` at path "/" as the root (ADR 0001).
  func showRootError(_ error: NavigationError, build: UInt) async {
    let formatted = await audioBrowser.browserManager.formatted(error, path: "/")
    guard isCurrent(build) else { return }
    interfaceController.safeSetRoot(CPListTemplate.message(formatted.title, subtitle: formatted.message), animated: true)
  }
}

/// Loads or refetches a tab's list when it is selected, which need not report it appearing.
final class TabBarDelegate: NSObject, CPTabBarTemplateDelegate {
  private weak var session: CarPlaySession?

  init(session: CarPlaySession) {
    self.session = session
  }

  func tabBarTemplate(_: CPTabBarTemplate, didSelect selectedTemplate: CPTemplate) {
    MainActor.assumeIsolated {
      guard let list = selectedTemplate as? CPListTemplate else { return }
      session?.loadContentIfNeeded(for: list)
    }
  }
}
