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

  /// Current navigation stack paths (for back navigation context)
  private var navigationStack: [String] = []

  /// Helper object for CPInterfaceControllerDelegate conformance
  private var interfaceDelegate: InterfaceControllerDelegate?

  /// Image loading service for CarPlay artwork
  private var imageLoader: CarPlayImageLoader?

  /// Now Playing template and button management
  private let nowPlayingManager: CarPlayNowPlayingManager

  /// Convenience accessor for browser config
  private var config: BrowserConfig {
    audioBrowser?.browserManager.config ?? BrowserConfig()
  }

  /// Checks if the given src matches the currently active (loaded) track
  private func isActiveTrack(src: String) -> Bool {
    audioBrowser?.getPlayer()?.currentTrack?.src == src
  }

  // MARK: - Initialization

  @objc
  public init(interfaceController: CPInterfaceController) {
    self.interfaceController = interfaceController
    audioBrowser = HybridAudioBrowser.shared
    nowPlayingManager = CarPlayNowPlayingManager(interfaceController: interfaceController)
    super.init()
    nowPlayingManager.listItemFactory = { [weak self] track, handler in
      self?.createListItem(for: track, handler: handler) ?? CPListItem(text: track.title, detailText: nil)
    }
  }

  // MARK: - Lifecycle

  @objc
  public func start() {
    guard !isStarted else { return }
    isStarted = true

    logger.info("Starting CarPlay controller")

    // Set up interface controller delegate for template lifecycle events
    let delegate = InterfaceControllerDelegate(controller: self)
    interfaceDelegate = delegate
    interfaceController.delegate = delegate

    // Show loading template while waiting
    showLoadingTemplate()

    // Wait for both browser and player to be ready
    Task { @MainActor in
      let (browser, _) = await playerAndConfiguredBrowser.wait()
      guard self.isStarted else { return }
      self.logger.debug("AudioBrowser and player ready, setting up CarPlay")
      self.audioBrowser = browser
      self.trackSelector = TrackSelector(browserManager: browser.browserManager)

      // Create image loader with CarPlay display traits
      self.imageLoader = CarPlayImageLoader(
        carTraitCollection: self.interfaceController.carTraitCollection,
        browserManager: browser.browserManager
      )

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

    nowPlayingManager.teardown()

    navigationStack.removeAll()
  }

  // MARK: - Content Subscriptions

  private func setupContentSubscriptions() {
    guard let audioBrowser else {
      logger.warning("AudioBrowser not available for CarPlay")
      return
    }

    // Subscribe to tab changes
    audioBrowser.tabsChangedEmitter.addListener { [weak self] tabs in
      Task { @MainActor in
        self?.handleTabsChanged(tabs)
      }
    }

    // Subscribe to content changes
    audioBrowser.contentChangedEmitter.addListener { [weak self] content in
      Task { @MainActor in
        self?.handleContentChanged(content)
      }
    }

    // Subscribe to config changes (for Now Playing buttons)
    audioBrowser.browserManager.onConfigChanged = { [weak self] _ in
      Task { @MainActor in
        self?.nowPlayingManager.setupNowPlayingButtons()
      }
    }

    // Subscribe to favorite changes (for Now Playing button)
    audioBrowser.favoriteChangedEmitter.addListener { [weak self] _ in
      Task { @MainActor in
        self?.nowPlayingManager.updateFavoriteButtonState()
      }
    }

    // Subscribe to external content changes (from notifyContentChanged)
    audioBrowser.externalContentChangedEmitter.addListener { [weak self] path in
      self?.notifyContentChanged(path: path)
    }

    // Subscribe to active track changes (for playing indicator in lists)
    audioBrowser.activeTrackChangedEmitter.addListener { [weak self] event in
      Task { @MainActor in
        self?.handleActiveTrackChanged(event)
      }
    }

    // Subscribe to queue changes (for Up Next list updates)
    audioBrowser.queueChangedEmitter.addListener { [weak self] tracks in
      Task { @MainActor in
        self?.nowPlayingManager.handleQueueChanged(tracks)
      }
    }

    // Subscribe to navigation errors (from browser layer)
    audioBrowser.navigationErrorEmitter.addListener { [weak self] event in
      Task { @MainActor in
        self?.handleNavigationError(event)
      }
    }
  }

  /// Handles navigation errors from the browser layer, displaying them in CarPlay
  @MainActor
  private func handleNavigationError(_ event: NavigationErrorEvent) {
    guard let error = event.error else { return }
    logger.warning("Navigation error: \(error.code.stringValue) - \(error.message)")
    // Use current path from navigation stack, or "/" as fallback
    let path = navigationStack.last ?? "/"
    showNavigationError(error, path: path)
  }

  // MARK: - Initial Interface

  @MainActor
  private func buildInitialInterface() async {
    guard let audioBrowser else {
      logger.error("AudioBrowser not available")
      showErrorTemplate(message: "Audio browser not initialized")
      return
    }

    // Get tabs from browser manager
    let tabs = audioBrowser.browserManager.getTabs()

    if let tabs, !tabs.isEmpty {
      await showTabBar(tabs: tabs)
    } else {
      // No tabs yet - query them
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

  /// Shows a loading template while waiting for initialization
  private func showLoadingTemplate() {
    let template = CPListTemplate(
      title: nil,
      sections: [],
    )
    interfaceController.setRootTemplate(template, animated: false, completion: nil)
  }

  // MARK: - Tab Bar

  @MainActor
  private func showTabBar(tabs: [Track]) async {
    logger.info("Building tab bar with \(tabs.count) tabs")

    let maxTabs = CPTabBarTemplate.maximumTabCount

    // Create tab templates synchronously (empty shells) - don't block on content loading
    let tabTemplates: [CPListTemplate] = tabs.prefix(maxTabs).map { tab in
      createTabTemplate(for: tab)
    }

    // Set the tab bar immediately so UI appears fast
    logger.info("Setting tab bar root template with \(tabTemplates.count) templates")
    let tabBar = CPTabBarTemplate(templates: tabTemplates)
    interfaceController.setRootTemplate(tabBar, animated: true, completion: nil)

    // Load content for the first tab only - others load lazily when selected
    if let firstTemplate = tabTemplates.first, let firstTab = tabs.first, let url = firstTab.url {
      await loadContent(for: url, into: firstTemplate)
    }
  }

  /// Creates a tab template shell without loading content (synchronous)
  private func createTabTemplate(for track: Track) -> CPListTemplate {
    let template = CPListTemplate(
      title: track.title,
      sections: [],
    )

    // Set tab title explicitly (required for tab bar display)
    template.tabTitle = track.title

    // Store path for lazy loading and refresh
    if let url = track.url {
      template.userInfo = ["path": url] as [String: Any]
    }

    // Set tab image - CarPlay requires an image for proper tab display
    // Tab bar icons are 24pt x 24pt per CarPlay Developer Guide
    // https://developer.apple.com/download/files/CarPlay-Developer-Guide.pdf
    template.tabImage = imageLoader?.defaultTabImage()

    if let artwork = track.artwork, SFSymbolRenderer.isSFSymbol(artwork) {
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
    }

    return template
  }

  // MARK: - List Templates

  @MainActor
  private func createListTemplate(
    for resolvedTrack: ResolvedTrack,
    path: String,
  ) -> CPListTemplate {
    let template = CPListTemplate(
      title: resolvedTrack.title,
      sections: [],
    )
    updateTemplate(template, with: resolvedTrack)
    template.userInfo = ["path": path] as [String: Any]

    return template
  }

  /// Finds the path associated with a template, if any
  private func getPath(from template: CPTemplate) -> String? {
    (template.userInfo as? [String: Any])?["path"] as? String
  }

  /// Updates a template's sections and assistant cell from resolved content.
  private func updateTemplate(_ template: CPListTemplate, with resolvedTrack: ResolvedTrack) {
    let sections = createSections(from: resolvedTrack)
    template.updateSections(sections)
    configureAssistantCell(on: template, from: resolvedTrack)
  }

  /// Configures the assistant cell ("Ask Siri to Play Audio") on a template
  /// based on the `carPlaySiriListButton` property of the resolved content.
  private func configureAssistantCell(on template: CPListTemplate, from resolvedTrack: ResolvedTrack) {
    guard #available(iOS 15.4, *) else { return }
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

  private func createSections(from resolvedTrack: ResolvedTrack) -> [CPListSection] {
    guard let children = resolvedTrack.children else {
      return []
    }

    let maxSections = CPListTemplate.maximumSectionCount
    let maxTotalItems = CPListTemplate.maximumItemCount

    // Group by groupTitle if present
    var groups: [String?: [Track]] = [:]
    for track in children {
      let groupKey = track.groupTitle
      groups[groupKey, default: []].append(track)
    }

    // Create sections (respecting both section and total item limits)
    var sections: [CPListSection] = []
    var totalItemCount = 0

    // Ungrouped items first
    if let ungrouped = groups[nil], !ungrouped.isEmpty {
      let availableSlots = maxTotalItems - totalItemCount
      let items: [CPListTemplateItem] = ungrouped.prefix(availableSlots).map { track in
        if track.imageRow != nil {
          return createImageRowItem(for: track)
        }
        return createListItem(for: track)
      }
      if !items.isEmpty {
        sections.append(CPListSection(items: items))
        totalItemCount += items.count
      }
    }

    // Then grouped items (respecting section and item limits)
    for (groupTitle, tracks) in groups.sorted(by: { ($0.key ?? "") < ($1.key ?? "") }) {
      guard sections.count < maxSections else { break }
      guard totalItemCount < maxTotalItems else { break }
      guard groupTitle != nil else { continue }

      let availableSlots = maxTotalItems - totalItemCount
      let items: [CPListTemplateItem] = tracks.prefix(availableSlots).map { track in
        if track.imageRow != nil {
          return createImageRowItem(for: track)
        }
        return createListItem(for: track)
      }
      if !items.isEmpty {
        sections.append(CPListSection(items: items, header: groupTitle, sectionIndexTitle: nil))
        totalItemCount += items.count
      }
    }

    return sections
  }

  /// Creates a CPListItem for a track with common setup (userInfo, artwork, isPlaying).
  /// - Parameters:
  ///   - track: The track to create the item for
  ///   - handler: Optional custom handler. If nil, uses default browse/play handling.
  fileprivate func createListItem(
    for track: Track,
    handler: ((CPSelectableListItem, @escaping () -> Void) -> Void)? = nil,
  ) -> CPListItem {
    let item = CPListItem(
      text: track.title,
      detailText: track.subtitle ?? track.artist,
    )

    // Store track info for selection handling and updatePlayingIndicators()
    item.userInfo = [
      "url": track.url as Any,
      "src": track.src as Any,
      "hasSrc": track.src != nil,
      "hasUrl": track.url != nil,
    ]

    // Set accessory type based on whether track is browsable or playable
    if let src = track.src {
      // Playable track - check if it's currently playing
      item.accessoryType = .none
      item.isPlaying = isActiveTrack(src: src)
      if item.isPlaying {
        logger.debug("Setting isPlaying=true for: \(track.title) (src: \(src))")
      }
    } else if track.url != nil {
      // Browsable only - show disclosure indicator
      item.accessoryType = .disclosureIndicator
    }

    // Load artwork with size context for proper CDN optimization
    if track.artwork != nil || track.artworkSource != nil {
      // Set empty placeholder to reserve space while loading
      item.setImage(imageLoader?.placeholderImage)
      imageLoader?.loadArtwork(for: track, size: CPListItem.maximumImageSize) { [weak item] image in
        Task { @MainActor in
          item?.setImage(image)
        }
      }
    }

    // Set selection handler
    if let handler {
      item.handler = handler
    } else {
      item.handler = { [weak self] _, completion in
        self?.handleItemSelection(track: track, completion: completion)
      }
    }

    return item
  }

  /// Creates a CPListImageRowItem for a track that has an imageRow.
  /// Renders as a horizontal row of tappable artwork thumbnails with a header title.
  private func createImageRowItem(for track: Track) -> CPListImageRowItem {
    guard let imageRowItems = track.imageRow else {
      fatalError("createImageRowItem called without imageRow")
    }

    // CPListImageRowItem requires images at init — start with placeholders
    let maxImages = CPMaximumNumberOfGridImages
    let visibleItems = Array(imageRowItems.prefix(maxImages))
    let placeholders = visibleItems.map { _ in imageLoader?.placeholderImage ?? UIImage() }
    let titles = visibleItems.map(\.title)

    // Use imageTitles variant on iOS 17.4+ to show titles below each thumbnail
    let item = if #available(iOS 17.4, *) {
      CPListImageRowItem(text: track.title, images: placeholders, imageTitles: titles)
    } else {
      CPListImageRowItem(text: track.title, images: placeholders)
    }

    // Store track info for identification
    item.userInfo = [
      "url": track.url as Any,
      "src": track.src as Any,
      "hasSrc": track.src != nil,
      "hasUrl": track.url != nil,
    ]

    // Handler for row header tap → navigate to track.url if present
    item.handler = { [weak self] _, completion in
      self?.handleItemSelection(track: track, completion: completion)
    }

    // Handler for individual image taps
    item.listImageRowHandler = { [weak self] _, index, completion in
      guard let self, index < visibleItems.count else {
        completion()
        return
      }
      let tappedItem = visibleItems[index]
      // Create a minimal Track to reuse handleItemSelection
      let itemTrack = Track(
        url: tappedItem.url,
        src: nil,
        artwork: tappedItem.artwork,
        artworkSource: tappedItem.artworkSource,
        artworkCarPlayTinted: nil,
        title: tappedItem.title,
        subtitle: nil,
        artist: nil,
        album: nil,
        description: nil,
        genre: nil,
        duration: nil,
        style: nil,
        childrenStyle: nil,
        favorited: nil,
        groupTitle: nil,
        live: nil,
        imageRow: nil,
      )
      self.handleItemSelection(track: itemTrack, completion: completion)
    }

    // Load artwork for each visible image row item asynchronously
    for (index, imageRowItem) in visibleItems.enumerated() {
      guard imageRowItem.artwork != nil || imageRowItem.artworkSource != nil else { continue }

      // Create a minimal Track for the artwork loader
      let itemTrack = Track(
        url: imageRowItem.url,
        src: nil,
        artwork: imageRowItem.artwork,
        artworkSource: imageRowItem.artworkSource,
        artworkCarPlayTinted: nil,
        title: imageRowItem.title,
        subtitle: nil,
        artist: nil,
        album: nil,
        description: nil,
        genre: nil,
        duration: nil,
        style: nil,
        childrenStyle: nil,
        favorited: nil,
        groupTitle: nil,
        live: nil,
        imageRow: nil,
      )

      imageLoader?.loadArtwork(for: itemTrack, size: CPListImageRowItem.maximumImageSize) { [weak item] image in
        Task { @MainActor in
          guard let item, let image else { return }
          var images = item.gridImages
          if index < images.count {
            images[index] = image
            item.update(images)
          }
        }
      }
    }

    return item
  }

  // MARK: - Content Loading

  @MainActor
  private func loadContent(for path: String, into template: CPListTemplate) async {
    guard let audioBrowser else { return }

    do {
      let resolved = try await audioBrowser.browserManager.resolve(path, useCache: true)
      updateTemplate(template, with: resolved)
    } catch {
      logger.error("Failed to load content for \(path): \(error.localizedDescription)")
    }
  }

  // MARK: - Selection Handling

  private func handleItemSelection(track: Track, completion: @escaping () -> Void) {
    logger.info("Selected track: \(track.title)")

    guard let audioBrowser else {
      completion()
      return
    }

    // If this track is already loaded, resume playback and show Now Playing
    if let src = track.src, isActiveTrack(src: src) {
      try? audioBrowser.play()
      nowPlayingManager.showNowPlaying()
      completion()
      return
    }

    guard let player = audioBrowser.getPlayer(), let trackSelector else {
      completion()
      return
    }

    Task {
      let result = await trackSelector.select(track: track, player: player)
      await MainActor.run {
        switch result {
        case .play(let intent):
          self.executePlayback(intent, player: player)
          self.nowPlayingManager.showNowPlaying()
        case .intercepted:
          self.nowPlayingManager.showNowPlaying()
        case .browse(let url):
          self.navigateToUrl(url, completion: completion)
          return // navigateToUrl handles its own completion
        case .none:
          break
        }
        completion()
      }
    }
  }

  private func executePlayback(_ intent: TrackSelector.PlaybackIntent, player: TrackPlayer) {
    switch intent {
    case .skipTo(let index):
      try? player.skipTo(index, playWhenReady: true)
    case .setQueue(let tracks, let startIndex, let sourcePath):
      player.setQueue(tracks, initialIndex: startIndex, playWhenReady: true, sourcePath: sourcePath)
    case .loadTrack(let track):
      player.load(track, playWhenReady: true)
    }
  }

  /// Navigates to a browsable URL path, showing error action sheet on failure with retry option.
  private func navigateToUrl(_ url: String, completion: @escaping () -> Void) {
    guard let audioBrowser else {
      completion()
      return
    }

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
          let navError = NavigationError.from(error)
          self.showNavigationError(navError, path: url)
          completion()
        }
      }
    }
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
    // This callback fires when the main browser's content changes.
    // For CarPlay-specific refreshes (e.g., favorites), use notifyContentChanged instead.
    guard let content else { return }
    refreshTemplatesForPath(content.url, with: content)
  }

  @MainActor
  private func handleActiveTrackChanged(_ event: PlaybackActiveTrackChangedEvent) {
    logger.debug("handleActiveTrackChanged: \(event.lastTrack?.src ?? "nil") → \(event.track?.src ?? "nil")")
    updatePlayingIndicators()
    // Update favorite button to reflect the new track's favorite state
    nowPlayingManager.updateFavoriteButtonState()
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
      for section in template.sections {
        for item in section.items {
          guard let listItem = item as? CPListItem,
                let userInfo = listItem.userInfo as? [String: Any],
                let itemSrc = userInfo["src"] as? String
          else { continue }

          let isPlaying = isActiveTrack(src: itemSrc)
          if listItem.isPlaying != isPlaying {
            logger.debug("Updating isPlaying for \(itemSrc): \(listItem.isPlaying) → \(isPlaying)")
            listItem.isPlaying = isPlaying
          }
        }
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
      interfaceController.dismissTemplate(animated: false) { [weak self] _, _ in
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
      self?.interfaceController.dismissTemplate(animated: true, completion: nil)
    }

    let actionSheet = CPActionSheetTemplate(
      title: customDisplay.title,
      message: customDisplay.message,
      actions: [ok],
    )

    interfaceController.presentTemplate(actionSheet, animated: true, completion: nil)
  }

  /// Shows a simple error template as root (for initialization errors when no other template exists)
  private func showErrorTemplate(message: String) {
    // CPAlertTemplate cannot be set as root - use a list template instead
    let errorItem = CPListItem(text: message, detailText: nil)
    errorItem.isEnabled = false
    let template = CPListTemplate(
      title: "Error",
      sections: [CPListSection(items: [errorItem])],
    )
    interfaceController.setRootTemplate(template, animated: true, completion: nil)
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
    guard let listTemplate = aTemplate as? CPListTemplate else { return }

    // Update playing indicators when navigating back to a list template
    controller?.updatePlayingIndicators()

    // Lazy load content for tabs that haven't been loaded yet
    controller?.loadContentIfNeeded(for: listTemplate)
  }
}

// MARK: - Lazy Loading

private extension RNABCarPlayController {
  /// Loads content for a template if it hasn't been loaded yet (lazy loading for tabs)
  func loadContentIfNeeded(for template: CPListTemplate) {
    // Skip if already has content
    guard template.sections.isEmpty else { return }

    // Get path from userInfo
    guard let path = getPath(from: template) else { return }

    logger.debug("Lazy loading content for tab: \(path)")

    Task {
      await loadContent(for: path, into: template)
    }
  }
}

// MARK: - UIImage Resize

extension UIImage {
  /// Draws the image centered within the target size, maintaining aspect ratio
  func resized(to targetSize: CGSize) -> UIImage? {
    UIGraphicsBeginImageContextWithOptions(targetSize, false, 0.0)
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
