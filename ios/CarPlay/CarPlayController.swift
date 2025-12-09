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
/// - Loads artwork for list items
/// - Integrates with CPNowPlayingTemplate
///
/// This class is exposed to Objective-C for use by RNABCarPlaySceneDelegate.
@objc(RNABCarPlayController)
public final class RNABCarPlayController: NSObject {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "CarPlayController")

  private let interfaceController: CPInterfaceController
  private weak var audioBrowser: HybridAudioBrowser?

  /// Track content subscriptions
  private var isStarted = false

  /// Current navigation stack paths (for back navigation context)
  private var navigationStack: [String] = []

  // MARK: - Initialization

  @objc
  public init(interfaceController: CPInterfaceController) {
    self.interfaceController = interfaceController
    self.audioBrowser = HybridAudioBrowser.shared
    super.init()
  }

  // MARK: - Lifecycle

  @objc
  public func start() {
    guard !isStarted else { return }
    isStarted = true

    logger.info("Starting CarPlay controller")

    // Subscribe to browser content changes
    setupContentSubscriptions()

    // Build initial interface
    Task { @MainActor in
      await buildInitialInterface()
    }
  }

  @objc
  public func stop() {
    guard isStarted else { return }
    isStarted = false

    logger.info("Stopping CarPlay controller")
    navigationStack.removeAll()
  }

  // MARK: - Content Subscriptions

  private func setupContentSubscriptions() {
    guard let audioBrowser = audioBrowser else {
      logger.warning("AudioBrowser not available for CarPlay")
      return
    }

    // Subscribe to tab changes
    let originalOnTabsChanged = audioBrowser.onTabsChanged
    audioBrowser.onTabsChanged = { [weak self, originalOnTabsChanged] tabs in
      originalOnTabsChanged(tabs)
      Task { @MainActor in
        self?.handleTabsChanged(tabs)
      }
    }

    // Subscribe to content changes
    let originalOnContentChanged = audioBrowser.onContentChanged
    audioBrowser.onContentChanged = { [weak self, originalOnContentChanged] content in
      originalOnContentChanged(content)
      Task { @MainActor in
        self?.handleContentChanged(content)
      }
    }
  }

  // MARK: - Initial Interface

  @MainActor
  private func buildInitialInterface() async {
    guard let audioBrowser = audioBrowser else {
      logger.error("AudioBrowser not available")
      showErrorTemplate(message: "Audio browser not initialized")
      return
    }

    // Get tabs from browser manager
    let tabs = audioBrowser.browserManager.getTabs()

    if let tabs = tabs, !tabs.isEmpty {
      await showTabBar(tabs: tabs)
    } else {
      // No tabs yet - show loading or query tabs
      logger.info("No tabs available, querying...")
      do {
        let queriedTabs = try await audioBrowser.browserManager.queryTabs()
        if !queriedTabs.isEmpty {
          await showTabBar(tabs: queriedTabs)
        } else {
          showErrorTemplate(message: "No content available")
        }
      } catch {
        logger.error("Failed to query tabs: \(error.localizedDescription)")
        showErrorTemplate(message: "Failed to load content")
      }
    }
  }

  // MARK: - Tab Bar

  @MainActor
  private func showTabBar(tabs: [Track]) async {
    logger.info("Building tab bar with \(tabs.count) tabs")

    var tabTemplates: [CPTemplate] = []
    let maxTabs = CPTabBarTemplate.maximumTabCount

    for tab in tabs.prefix(maxTabs) {
      let listTemplate = await createListTemplate(for: tab)
      tabTemplates.append(listTemplate)
    }

    let tabBar = CPTabBarTemplate(templates: tabTemplates)
    interfaceController.setRootTemplate(tabBar, animated: true, completion: nil)
  }

  // MARK: - List Templates

  @MainActor
  private func createListTemplate(for track: Track) async -> CPListTemplate {
    let template = CPListTemplate(
      title: track.title,
      sections: []
    )

    // Set tab title explicitly (required for tab bar display)
    template.tabTitle = track.title

    // Set tab image - CarPlay requires an image for proper tab display
    if let artwork = track.artwork, let url = URL(string: artwork) {
      if let image = await loadImage(from: url) {
        template.tabImage = image
      } else {
        template.tabImage = defaultTabImage()
      }
    } else {
      // Default icon when no artwork available
      template.tabImage = defaultTabImage()
    }

    // If track has a URL, resolve its content
    if let url = track.url {
      await loadContent(for: url, into: template)
    }

    return template
  }

  @MainActor
  private func createListTemplate(
    for resolvedTrack: ResolvedTrack,
    path: String
  ) -> CPListTemplate {
    let template = CPListTemplate(
      title: resolvedTrack.title,
      sections: createSections(from: resolvedTrack)
    )

    template.userInfo = ["path": path] as [String: Any]

    return template
  }

  private func createSections(from resolvedTrack: ResolvedTrack) -> [CPListSection] {
    guard let children = resolvedTrack.children else {
      return []
    }

    // Group by groupTitle if present
    var groups: [String?: [Track]] = [:]
    for track in children {
      let groupKey = track.groupTitle
      groups[groupKey, default: []].append(track)
    }

    // Create sections
    var sections: [CPListSection] = []

    // Ungrouped items first
    if let ungrouped = groups[nil], !ungrouped.isEmpty {
      let items = ungrouped.map { createListItem(for: $0) }
      sections.append(CPListSection(items: items))
    }

    // Then grouped items
    for (groupTitle, tracks) in groups.sorted(by: { ($0.key ?? "") < ($1.key ?? "") }) {
      guard groupTitle != nil else { continue }
      let items = tracks.map { createListItem(for: $0) }
      sections.append(CPListSection(items: items, header: groupTitle, sectionIndexTitle: nil))
    }

    return sections
  }

  private func createListItem(for track: Track) -> CPListItem {
    let item = CPListItem(
      text: track.title,
      detailText: track.subtitle ?? track.artist
    )

    // Store track info for selection handling
    item.userInfo = [
      "url": track.url as Any,
      "src": track.src as Any,
      "hasSrc": track.src != nil,
      "hasUrl": track.url != nil
    ]

    // Set accessory type based on whether track is browsable or playable
    if track.src != nil {
      // Playable track
      item.accessoryType = .none
      item.isPlaying = false  // Will be updated based on current playback
    } else if track.url != nil {
      // Browsable only - show disclosure indicator
      item.accessoryType = .disclosureIndicator
    }

    // Load artwork asynchronously
    if let artworkUrl = track.artwork ?? track.artworkSource?.uri,
       let url = URL(string: artworkUrl) {
      Task {
        if let image = await loadImage(from: url) {
          await MainActor.run {
            item.setImage(image)
          }
        }
      }
    }

    // Set selection handler
    item.handler = { [weak self] _, completion in
      self?.handleItemSelection(track: track, completion: completion)
    }

    return item
  }

  // MARK: - Content Loading

  @MainActor
  private func loadContent(for path: String, into template: CPListTemplate) async {
    guard let audioBrowser = audioBrowser else { return }

    do {
      let resolved = try await audioBrowser.browserManager.resolve(path, useCache: true)
      let sections = createSections(from: resolved)
      template.updateSections(sections)
    } catch {
      logger.error("Failed to load content for \(path): \(error.localizedDescription)")
    }
  }

  // MARK: - Selection Handling

  private func handleItemSelection(track: Track, completion: @escaping () -> Void) {
    logger.info("Selected track: \(track.title)")

    guard let audioBrowser = audioBrowser else {
      completion()
      return
    }

    // Check if this is a contextual URL (playable-only track with queue context)
    if let url = track.url, BrowserPathHelper.isContextual(url) {
      Task {
        do {
          // Expand the queue from the contextual URL
          if let expanded = try await audioBrowser.browserManager.expandQueueFromContextualUrl(url) {
            let (tracks, startIndex) = expanded

            await MainActor.run {
              // Replace queue and seek to selected track
              audioBrowser.getPlayer()?.clear()
              audioBrowser.getPlayer()?.add(tracks)
              try? audioBrowser.getPlayer()?.skipTo(startIndex)
              audioBrowser.getPlayer()?.play()

              // Show now playing template
              self.showNowPlaying()
              completion()
            }
          } else {
            // Fallback: just load the single track
            await MainActor.run {
              try? audioBrowser.load(track: track)
              try? audioBrowser.play()
              self.showNowPlaying()
              completion()
            }
          }
        } catch {
          logger.error("Error expanding queue: \(error.localizedDescription)")
          await MainActor.run {
            try? audioBrowser.load(track: track)
            try? audioBrowser.play()
            self.showNowPlaying()
            completion()
          }
        }
      }
    }
    // If track has src, it's playable - load it
    else if track.src != nil {
      Task { @MainActor in
        try? audioBrowser.load(track: track)
        try? audioBrowser.play()
        showNowPlaying()
        completion()
      }
    }
    // If track has url, it's browsable - navigate to it
    else if let url = track.url {
      Task {
        do {
          let resolved = try await audioBrowser.browserManager.resolve(url, useCache: true)

          await MainActor.run {
            let listTemplate = self.createListTemplate(for: resolved, path: url)
            self.navigationStack.append(url)
            self.interfaceController.pushTemplate(listTemplate, animated: true, completion: nil)
            completion()
          }
        } catch {
          logger.error("Failed to navigate to \(url): \(error.localizedDescription)")
          await MainActor.run {
            completion()
          }
        }
      }
    } else {
      completion()
    }
  }

  // MARK: - Now Playing

  private func showNowPlaying() {
    let nowPlayingTemplate = CPNowPlayingTemplate.shared
    interfaceController.pushTemplate(nowPlayingTemplate, animated: true, completion: nil)
  }

  // MARK: - Content Change Handlers

  @MainActor
  private func handleTabsChanged(_ tabs: [Track]) {
    logger.debug("Tabs changed: \(tabs.count) tabs")
    // Rebuild tab bar if we're at the root
    Task {
      await showTabBar(tabs: tabs)
    }
  }

  @MainActor
  private func handleContentChanged(_ content: ResolvedTrack?) {
    // Update the current template if it matches the content path
    // This is useful for refreshing content after favorites change, etc.
  }

  // MARK: - Error Handling

  private func showErrorTemplate(message: String) {
    let alertAction = CPAlertAction(title: "OK", style: .default) { _ in }
    let alertTemplate = CPAlertTemplate(titleVariants: [message], actions: [alertAction])
    interfaceController.setRootTemplate(alertTemplate, animated: true, completion: nil)
  }

  // MARK: - Image Loading

  private func defaultTabImage() -> UIImage? {
    let config = UIImage.SymbolConfiguration(scale: .large)
    return UIImage(systemName: "music.note.list", withConfiguration: config)
  }

  private func loadImage(from url: URL) async -> UIImage? {
    do {
      let (data, _) = try await URLSession.shared.data(from: url)
      return UIImage(data: data)
    } catch {
      logger.debug("Failed to load image from \(url): \(error.localizedDescription)")
      return nil
    }
  }
}

