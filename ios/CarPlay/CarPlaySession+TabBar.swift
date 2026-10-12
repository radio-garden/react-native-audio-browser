import CarPlay
import NitroModules

extension CarPlaySession {
  /// The tabs the tab bar shows: a disabled tab hides, as `CPTabBarTemplate` has no disabled
  /// state (Android Auto filters the same way).
  static func visibleTabs(_ tabs: [Track]) -> [Track] {
    tabs.filter { $0.disabled != true }
  }

  func buildInitialInterface() async {
    let build = beginRootBuild()
    isBuildingInitialInterface = true
    defer { isBuildingInitialInterface = false }
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
    let tabs = Array(Self.visibleTabs(allTabs).prefix(CPTabBarTemplate.maximumTabCount))
    let gated = audioBrowser.browseGate.isActive

    // A tab bar with no tabs renders blank. A gate shows one full-screen page instead;
    // otherwise it is the empty-content error, like any other failure (ADR 0001).
    guard !tabs.isEmpty else {
      if gated, let chrome = await audioBrowser.browseGate.decide(NativeGateRequest(reason: .browse, path: nil, search: nil)) {
        guard isCurrent(build) else { return }
        audioBrowser.onGate(GateEvent(reason: .browse))
        interfaceController.safeSetRoot(makeGatePage(chrome, path: nil, tab: nil), animated: true)
      } else {
        await showRootError(.code(.emptyContent), build: build)
      }
      return
    }

    let chromes = gated ? await gates(for: tabs) : tabs.map { _ in nil }
    guard isCurrent(build) else { return }
    for _ in chromes.compactMap(\.self) {
      audioBrowser.onGate(GateEvent(reason: .browse))
    }
    let templates = zip(tabs, chromes).map { tab, chrome in
      chrome.map { makeGatePage($0, path: tab.path, tab: tab) } ?? makeTabTemplate(for: tab)
    }

    if gated, let tabBar = interfaceController.rootTemplate as? CPTabBarTemplate,
       tabBar.templates.count == templates.count
    {
      // An equal-count swap keeps the selected tab.
      tabBar.updateTemplates(templates)
    } else {
      interfaceController.safeSetRoot(CPTabBarTemplate(templates: templates), animated: true)
    }

    // Tabs fill lazily on first appearance. The first one is on screen already, and a template
    // swapped in by `updateTemplates` may never report appearing.
    let eager = gated ? templates : Array(templates.prefix(1))
    for template in eager {
      guard let path = template.contentPath else { continue }
      await loadContent(for: path, into: template)
    }
  }

  /// The tabs changed: restamp their entries in place when the paths are the same, else
  /// rebuild, once the user is back at the root.
  func tabsChanged(_ tabs: [Track]) {
    // The initial build queried these tabs and builds from them itself.
    guard !isBuildingInitialInterface else { return }
    let visible = Self.visibleTabs(tabs)
    if !audioBrowser.browseGate.isActive,
       let tabBar = interfaceController.rootTemplate as? CPTabBarTemplate,
       tabBar.templates.count == min(visible.count, CPTabBarTemplate.maximumTabCount),
       zip(tabBar.templates, visible).allSatisfy({ $0.contentPath == $1.path })
    {
      for (template, tab) in zip(tabBar.templates, visible) {
        applyTabBarEntry(to: template, for: tab)
      }
      needsTabRebuild = false
      return
    }
    needsTabRebuild = true
    _ = rebuildTabsIfAtRoot()
  }

  /// Rebuilds the tab bar if a rebuild is due and the user is at the root. True when it did,
  /// so the caller can skip work on a template about to be replaced.
  func rebuildTabsIfAtRoot() -> Bool {
    guard needsTabRebuild, interfaceController.templates.count <= 1 else { return false }
    needsTabRebuild = false
    spawn { await $0.showTabBar($0.audioBrowser.browserManager.tabs ?? []) }
    return true
  }

  /// The gate was set or cleared: pushed content must not stay reachable behind it, and a
  /// clear returns to the root.
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

  /// The chrome to show if the Browse Gate gates this request, reporting it to JS; nil when the
  /// request is allowed.
  func gate(_ reason: GateReason, path: String?, search: SearchParams? = nil) async -> Gate? {
    let chrome = await audioBrowser.browseGate.decide(NativeGateRequest(reason: reason, path: path, search: search))
    if chrome != nil { audioBrowser.onGate(GateEvent(reason: reason)) }
    return chrome
  }

  /// One decision per tab, made concurrently and returned in tab order. Reports nothing: the
  /// caller fires `onGate` once it knows its build is still current.
  private func gates(for tabs: [Track]) async -> [Gate?] {
    let browseGate = audioBrowser.browseGate
    let decisions = tabs.map { tab in
      Task<Gate?, Never> { @MainActor in
        await browseGate.decide(NativeGateRequest(reason: .browse, path: tab.path, search: nil))
      }
    }
    var chromes: [Gate?] = []
    for decision in decisions {
      await chromes.append(decision.value)
    }
    return chromes
  }

  /// A tab's content shell, which loads when it first appears.
  private func makeTabTemplate(for tab: Track) -> CPListTemplate {
    let template = CPListTemplate.loading(title: tab.title, path: tab.path, loadingTitle: config.carPlayLoadingTitle)
    applyTabBarEntry(to: template, for: tab)
    return template
  }

  /// The gate page: a list template, because the audio entitlement does not allow
  /// `CPInformationTemplate` (the tab bar raises on one), showing the chrome as its centered
  /// empty view. No action button: a gate withholds content, and the app offers any
  /// "subscribe" in its own UI.
  func makeGatePage(_ chrome: Gate, path: String?, tab: Track?) -> CPListTemplate {
    let template = CPListTemplate.message(
      chrome.title, subtitle: chrome.message, listTitle: chrome.title,
      info: CarPlayTemplateInfo(path: path, kind: .gate),
    )
    if let tab { applyTabBarEntry(to: template, for: tab) }
    return template
  }

  /// Stamps a template's tab-bar title and image: an SF Symbol, loaded artwork, or none. Tab
  /// icons are 24 pt (CarPlay Developer Guide).
  func applyTabBarEntry(to template: CPTemplate, for track: Track) {
    template.tabTitle = track.title
    if let artwork = track.artwork?.url, SFSymbolRenderer.isSFSymbol(artwork) {
      let (symbolName, _, _) = SFSymbolRenderer.parseArtwork(artwork)
      if let image = imageLoader.sfSymbolImage(symbolName) {
        template.tabImage = image
      }
    } else if track.artwork != nil || track.artworkSource != nil {
      imageLoader.loadArtwork(for: track, size: CGSize(width: 24, height: 24)) { [weak template] image in
        Task { @MainActor in
          if let image { template?.tabImage = image }
        }
      }
    } else {
      // A restamp must not leave an old icon on a now title-only tab.
      template.tabImage = nil
    }
  }

  /// Shows a navigation error as the root: the app's formatting of it at path "/" (ADR 0001).
  func showRootError(_ error: NavigationError, build: UInt) async {
    let formatted = await audioBrowser.browserManager.formatted(error, path: "/")
    guard isCurrent(build) else { return }
    interfaceController.safeSetRoot(CPListTemplate.message(formatted.title, subtitle: formatted.message), animated: true)
  }
}
