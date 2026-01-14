import CarPlay
import Foundation
import Kingfisher
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
@MainActor
@objc(RNABCarPlayController)
public final class RNABCarPlayController: NSObject {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "CarPlayController")

  private let interfaceController: CPInterfaceController
  private weak var audioBrowser: HybridAudioBrowser?

  /// Track content subscriptions
  private var isStarted = false

  /// Current navigation stack paths (for back navigation context)
  private var navigationStack: [String] = []

  /// Helper object for CPNowPlayingTemplateObserver conformance
  /// (kept separate to avoid exposing CarPlay protocols to Obj-C header)
  private var nowPlayingObserver: NowPlayingObserver?

  /// Helper object for CPInterfaceControllerDelegate conformance
  private var interfaceDelegate: InterfaceControllerDelegate?

  /// Reference to the Up Next template for updating when queue changes
  private weak var upNextTemplate: CPListTemplate?

  /// Convenience accessor for browser config
  private var config: BrowserConfig {
    audioBrowser?.browserManager.config ?? BrowserConfig()
  }

  /// Gets the current active track's favorited state
  private var isActiveTrackFavorited: Bool {
    (try? audioBrowser?.getActiveTrack())?.favorited ?? false
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
    super.init()
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
      self.setupContentSubscriptions()
      self.setupNowPlayingTemplate()
      await self.buildInitialInterface()
    }
  }

  @objc
  public func stop() {
    guard isStarted else { return }
    isStarted = false

    logger.info("Stopping CarPlay controller")

    // Remove Now Playing observer
    if let observer = nowPlayingObserver {
      CPNowPlayingTemplate.shared.remove(observer)
      nowPlayingObserver = nil
    }

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
        self?.setupNowPlayingButtons()
      }
    }

    // Subscribe to favorite changes (for Now Playing button)
    audioBrowser.favoriteChangedEmitter.addListener { [weak self] _ in
      Task { @MainActor in
        self?.updateFavoriteButtonState()
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
        self?.handleQueueChanged(tracks)
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

    // Reserve one slot for search if configured
    let hasSearch = config.hasSearch
    let maxContentTabs = hasSearch ? maxTabs - 1 : maxTabs

    // Create tab templates synchronously (empty shells) - don't block on content loading
    let tabTemplates: [CPListTemplate] = tabs.prefix(maxContentTabs).map { tab in
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
    template.tabImage = defaultTabImage()

    // Support SF Symbols via "sf:" prefix (e.g., "sf:heart.fill")
    if let artwork = track.artwork, artwork.hasPrefix("sf:") {
      let symbolName = String(artwork.dropFirst(3))
      if let image = sfSymbolImage(symbolName) {
        template.tabImage = image
      }
    } else if track.artwork != nil || track.artworkSource != nil {
      // loadArtwork handles both artwork and artworkSource
      let tabImageSize = CGSize(width: 24, height: 24)
      loadArtwork(for: track, size: tabImageSize) { [weak template] image in
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
      sections: createSections(from: resolvedTrack),
    )

    template.userInfo = ["path": path] as [String: Any]

    return template
  }

  /// Finds the path associated with a template, if any
  private func getPath(from template: CPTemplate) -> String? {
    (template.userInfo as? [String: Any])?["path"] as? String
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
      let items = ungrouped.prefix(availableSlots).map { createListItem(for: $0) }
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
      let items = tracks.prefix(availableSlots).map { createListItem(for: $0) }
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
  private func createListItem(
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
    // Support SF Symbols via "sf:" prefix (e.g., "sf:heart.fill")
    if let artwork = track.artwork, artwork.hasPrefix("sf:") {
      let symbolName = String(artwork.dropFirst(3))
      if let image = sfSymbolImageForListItem(symbolName) {
        item.setImage(image)
      }
    } else if track.artwork != nil || track.artworkSource != nil {
      // Set empty placeholder to reserve space while loading
      item.setImage(placeholderImage)
      loadArtwork(for: track, size: CPListItem.maximumImageSize) { [weak item] image in
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

  // MARK: - Content Loading

  @MainActor
  private func loadContent(for path: String, into template: CPListTemplate) async {
    guard let audioBrowser else { return }

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

    guard let audioBrowser else {
      completion()
      return
    }

    // If this track is already loaded, resume playback and show Now Playing
    if let src = track.src, isActiveTrack(src: src) {
      try? audioBrowser.play()
      showNowPlaying()
      completion()
      return
    }

    // Check if this is a contextual URL (playable-only track with queue context)
    if let url = track.url, BrowserPathHelper.isContextual(url) {
      let parentPath = BrowserPathHelper.stripTrackId(url)
      let trackId = BrowserPathHelper.extractTrackId(url)
      let player = audioBrowser.getPlayer()

      // Check if queue already came from this parent path - just skip to the track
      if let trackId,
         parentPath == player?.queueSourcePath,
         let index = player?.tracks.firstIndex(where: { $0.src == trackId })
      {
        logger.debug("Queue already from \(parentPath), skipping to index \(index)")
        try? player?.skipTo(index, playWhenReady: true)
        showNowPlaying()
        completion()
        return
      }

      Task {
        do {
          // Expand the queue from the contextual URL
          if let expanded = try await audioBrowser.browserManager.expandQueueFromContextualUrl(url) {
            let (tracks, startIndex) = expanded

            await MainActor.run {
              // Replace queue and start at the selected track
              audioBrowser.getPlayer()?.setQueue(tracks, initialIndex: startIndex, playWhenReady: true, sourcePath: parentPath)

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
      navigateToUrl(url, completion: completion)
    } else {
      completion()
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
          let navError = self.toNavigationError(error)
          self.showNavigationError(navError, path: url)
          completion()
        }
      }
    }
  }

  // MARK: - Now Playing

  private func setupNowPlayingTemplate() {
    let template = CPNowPlayingTemplate.shared

    // Create and register observer for Up Next button
    let observer = NowPlayingObserver(controller: self)
    nowPlayingObserver = observer
    template.add(observer)

    // Setup custom Now Playing buttons from config
    setupNowPlayingButtons()

    // Update button states based on config
    updateNowPlayingButtonStates()
  }

  /// Sets up custom Now Playing buttons based on configuration
  private func setupNowPlayingButtons() {
    let buttons = config.carPlayNowPlayingButtons
    logger.info("Setting up Now Playing buttons: \(buttons.map(\.stringValue))")

    guard !buttons.isEmpty else {
      CPNowPlayingTemplate.shared.updateNowPlayingButtons([])
      return
    }

    var nowPlayingButtons: [CPNowPlayingButton] = []

    for buttonType in buttons {
      switch buttonType {
      case .shuffle:
        let shuffleButton = CPNowPlayingShuffleButton { [weak self] _ in
          self?.handleShuffleButtonTapped()
        }
        nowPlayingButtons.append(shuffleButton)

      case .repeat:
        let repeatButton = CPNowPlayingRepeatButton { [weak self] _ in
          self?.handleRepeatButtonTapped()
        }
        nowPlayingButtons.append(repeatButton)

      case .favorite:
        let favoriteButton = CPNowPlayingImageButton(
          image: favoriteButtonImage(isFavorited: isActiveTrackFavorited),
        ) { [weak self] _ in
          self?.handleFavoriteButtonTapped()
        }
        nowPlayingButtons.append(favoriteButton)

      case .playbackRate:
        let rateButton = CPNowPlayingPlaybackRateButton { [weak self] _ in
          self?.handlePlaybackRateButtonTapped()
        }
        nowPlayingButtons.append(rateButton)
      }
    }

    CPNowPlayingTemplate.shared.updateNowPlayingButtons(nowPlayingButtons)
    logger.info("Updated Now Playing with \(nowPlayingButtons.count) custom button(s)")
  }

  /// Returns the appropriate image for the favorite button based on state
  /// Sized to CPNowPlayingButtonMaximumImageSize per Apple docs
  private func favoriteButtonImage(isFavorited: Bool) -> UIImage {
    let symbolName = isFavorited ? "heart.fill" : "heart"
    guard let image = UIImage(systemName: symbolName)?.resized(to: CPNowPlayingButtonMaximumImageSize) else {
      return UIImage()
    }
    return image
  }

  /// Handles shuffle button tap - toggles shuffle mode
  private func handleShuffleButtonTapped() {
    guard let player = audioBrowser?.getPlayer() else { return }

    let newEnabled = !player.shuffleEnabled
    player.shuffleEnabled = newEnabled
    logger.info("CarPlay shuffle mode changed: \(newEnabled)")
  }

  /// Handles repeat button tap - cycles through repeat modes
  private func handleRepeatButtonTapped() {
    guard let player = audioBrowser?.getPlayer() else { return }

    let currentMode = player.getRepeatMode()
    let newMode: RepeatMode = switch currentMode {
    case .off:
      .track
    case .track:
      .queue
    case .queue:
      .off
    }

    player.setRepeatMode(newMode)
    logger.info("CarPlay repeat mode changed: \(currentMode.stringValue) → \(newMode.stringValue)")
  }

  /// Handles favorite button tap - toggles favorite state of current track
  private func handleFavoriteButtonTapped() {
    try? audioBrowser?.toggleActiveTrackFavorited()
    logger.info("CarPlay favorite toggled")
    // Button appearance is updated via onFavoriteChanged subscription
  }

  /// Handles playback rate button tap - cycles through available rates
  private func handlePlaybackRateButtonTapped() {
    guard let audioBrowser, let player = audioBrowser.getPlayer() else { return }

    let rates = audioBrowser.playbackRates
    guard !rates.isEmpty else { return }

    let currentRate = Double(player.rate)
    let nextRate: Double

    // Find current rate index and cycle to next
    if let currentIndex = rates.firstIndex(where: { (currentRate - $0).magnitude < 0.01 }) {
      // Current rate is in the list - cycle to next
      let nextIndex = (currentIndex + 1) % rates.count
      nextRate = rates[nextIndex]
    } else {
      // Current rate not in list - find first rate greater than current, or wrap to first
      nextRate = rates.first { $0 > currentRate } ?? rates[0]
    }

    player.rate = Float(nextRate)
    logger.info("CarPlay playback rate changed: \(currentRate) → \(nextRate)")
  }

  /// Updates the favorite button appearance based on current track's favorite state
  private func updateFavoriteButtonState() {
    guard config.carPlayNowPlayingButtons.contains(.favorite) else { return }
    let favorited = isActiveTrackFavorited
    let buttons = CPNowPlayingTemplate.shared.nowPlayingButtons

    // Find and update the favorite button (it's a CPNowPlayingImageButton)
    for (index, button) in buttons.enumerated() {
      if button is CPNowPlayingImageButton {
        // Recreate the button with updated image
        let newFavoriteButton = CPNowPlayingImageButton(
          image: favoriteButtonImage(isFavorited: favorited),
        ) { [weak self] _ in
          self?.handleFavoriteButtonTapped()
        }

        var updatedButtons = buttons
        updatedButtons[index] = newFavoriteButton
        CPNowPlayingTemplate.shared.updateNowPlayingButtons(updatedButtons)
        break
      }
    }
  }

  /// Updates Now Playing button states based on config and current queue
  private func updateNowPlayingButtonStates() {
    updateNowPlayingUpNextButton()
    updateFavoriteButtonState()
  }

  /// Updates the Up Next button enabled state based on config and queue size
  private func updateNowPlayingUpNextButton() {
    let template = CPNowPlayingTemplate.shared
    template.isUpNextButtonEnabled = config.carPlayUpNextButton && (audioBrowser?.getPlayer()?.tracks.count ?? 0) > 1
  }

  private func showNowPlaying() {
    updateNowPlayingButtonStates()
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
    updateFavoriteButtonState()
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
        let sections = createSections(from: content)
        listTemplate.updateSections(sections)
      }
    }

    // Check if any template in the navigation stack matches the changed path
    // The top template is the currently visible one
    if let topTemplate = interfaceController.topTemplate as? CPListTemplate,
       let templatePath = getPath(from: topTemplate),
       templatePath == path
    {
      logger.info("Refreshing top template for path: \(path)")
      let sections = createSections(from: content)
      topTemplate.updateSections(sections)
    }
  }

  // MARK: - Error Handling

  /// Shows a navigation error using CPActionSheetTemplate.
  /// - Parameters:
  ///   - error: The NavigationError to display
  ///   - path: The path that was being navigated to when the error occurred
  private func showNavigationError(_ error: NavigationError, path: String) {
    let defaultFormatted = defaultFormattedError(error)

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

  /// Returns the default formatted error for the given navigation error
  private func defaultFormattedError(_ error: NavigationError) -> FormattedNavigationError {
    let title = switch error.code {
    case .contentNotFound:
      "Content Not Found"
    case .networkError:
      "Network Error"
    case .httpError:
      httpErrorTitle(statusCode: error.statusCode.map { Int($0) })
    case .callbackError:
      "Error"
    case .unknownError:
      "Error"
    }
    return FormattedNavigationError(title: title, message: error.message)
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

  /// Returns a localized title for HTTP errors based on status code
  private func httpErrorTitle(statusCode: Int?) -> String {
    guard let code = statusCode else { return "Server Error" }
    return HTTPURLResponse.localizedString(forStatusCode: code).capitalized
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

  /// Converts a generic Error (typically BrowserError) to a NavigationError
  private func toNavigationError(_ error: Error) -> NavigationError {
    if let browserError = error as? BrowserError {
      switch browserError {
      case .contentNotFound:
        NavigationError(code: .contentNotFound, message: browserError.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
      case let .httpError(code, _):
        NavigationError(code: .httpError, message: browserError.localizedDescription, statusCode: Double(code), statusCodeSuccess: (200 ... 299).contains(code))
      case .networkError:
        NavigationError(code: .networkError, message: browserError.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
      case .invalidConfiguration:
        NavigationError(code: .unknownError, message: browserError.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
      case .callbackError:
        NavigationError(code: .callbackError, message: browserError.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
      }
    } else if let httpError = error as? HttpClient.HttpException {
      // HTTP error from HttpClient (non-2xx response)
      NavigationError(code: .httpError, message: httpError.localizedDescription, statusCode: Double(httpError.code), statusCodeSuccess: (200 ... 299).contains(httpError.code))
    } else if error is URLError {
      // Network error (connection failed, timeout, no internet, etc.)
      NavigationError(code: .networkError, message: error.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
    } else {
      NavigationError(code: .unknownError, message: error.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
    }
  }

  // MARK: - Image Loading

  /// Creates an SF Symbol image for tabs - plain systemName, CarPlay handles tinting
  private func sfSymbolImage(_ symbolName: String) -> UIImage? {
    UIImage(systemName: symbolName)
  }

  /// Creates an SF Symbol image for list items with light/dark mode support
  private func sfSymbolImageForListItem(_ symbolName: String) -> UIImage? {
    guard let symbol = UIImage(systemName: symbolName) else { return nil }

    let size = symbol.size
    let scale = symbol.scale

    // Create both light and dark bitmap variants
    let lightImage = renderSymbolToBitmap(symbol, tintColor: .black, size: size, scale: scale)
    let darkImage = renderSymbolToBitmap(symbol, tintColor: .white, size: size, scale: scale)

    // Combine with UIImageAsset for automatic light/dark switching
    let asset = UIImageAsset()
    asset.register(lightImage, with: UITraitCollection(userInterfaceStyle: .light))
    asset.register(darkImage, with: UITraitCollection(userInterfaceStyle: .dark))

    return asset.image(with: interfaceController.carTraitCollection)
  }

  /// Renders an SF Symbol to a bitmap with the specified tint color
  private nonisolated func renderSymbolToBitmap(_ symbol: UIImage, tintColor: UIColor, size: CGSize, scale: CGFloat) -> UIImage {
    UIGraphicsBeginImageContextWithOptions(size, false, scale)
    defer { UIGraphicsEndImageContext() }

    tintColor.set()
    symbol.withRenderingMode(.alwaysTemplate).draw(in: CGRect(origin: .zero, size: size))

    guard let rendered = UIGraphicsGetImageFromCurrentImageContext() else {
      return symbol // Fallback to original if rendering fails
    }
    return rendered.withRenderingMode(.alwaysOriginal)
  }

  private func defaultTabImage() -> UIImage? {
    sfSymbolImage("music.note.list")
  }

  /// Cached empty placeholder image to reserve space while artwork loads
  private lazy var placeholderImage: UIImage? = {
    let size = CPListItem.maximumImageSize
    let scale = interfaceController.carTraitCollection.displayScale
    UIGraphicsBeginImageContextWithOptions(size, false, scale)
    defer { UIGraphicsEndImageContext() }
    return UIGraphicsGetImageFromCurrentImageContext()
  }()

  /// Loads artwork for a track with size context, using the artwork transform if configured.
  /// - Parameters:
  ///   - track: The track to load artwork for
  ///   - size: The target size in points (will be multiplied by CarPlay display scale)
  ///   - completion: Called with the loaded image, or nil on failure
  private func loadArtwork(for track: Track, size: CGSize, completion: @escaping @Sendable (UIImage?) -> Void) {
    guard let browserManager = audioBrowser?.browserManager else {
      // Fall back to direct URL loading
      loadArtworkDirect(track: track, completion: completion)
      return
    }

    // Convert points to pixels using CarPlay display scale (not iPhone screen scale)
    let carTraits = interfaceController.carTraitCollection
    let scale = carTraits.displayScale
    let imageContext = ImageContext(width: size.width * scale, height: size.height * scale)

    Task {
      // Resolve artwork URL with size context
      let imageSource = await browserManager.resolveArtworkUrl(
        track: track,
        perRouteConfig: nil,
        imageContext: imageContext,
      )

      await MainActor.run {
        if let imageSource {
          // Check for SF Symbol URI (e.g., "sf:heart.fill")
          if imageSource.uri.hasPrefix("sf:") {
            let symbolName = String(imageSource.uri.dropFirst(3))
            completion(self.sfSymbolImageForListItem(symbolName))
            return
          }

          // Parse URL - skip if invalid
          guard let url = URL(string: imageSource.uri) else {
            self.loadArtworkDirect(track: track, completion: completion)
            return
          }

          // Capture trait collection before async call
          let carTraitCollection = self.interfaceController.carTraitCollection

          // Load from resolved URL with any custom headers
          var options: KingfisherOptionsInfo = []
          if let headers = imageSource.headers, !headers.isEmpty {
            let modifier = AnyModifier { request in
              var request = request
              for (key, value) in headers {
                request.setValue(value, forHTTPHeaderField: key)
              }
              return request
            }
            options.append(.requestModifier(modifier))
          }

          // Add SVG processor if URL is an SVG
          if url.pathExtension.lowercased() == "svg" {
            options.append(.processor(SVGProcessor(size: nil, scale: carTraitCollection.displayScale)))
          }

          // Capture tinting preference before async call
          let shouldTint = track.artworkCarPlayTinted ?? false

          KingfisherManager.shared.retrieveImage(with: url, options: options) { result in
            if case let .success(imageResult) = result {
              let image = imageResult.image

              if shouldTint {
                // Apply light/dark tinting for monochrome icons
                completion(self.createAdaptiveImage(image, carTraitCollection: carTraitCollection))
              } else {
                // Regular images (photos, album art) - show as-is
                completion(image)
              }
            } else {
              completion(nil)
            }
          }
        } else {
          // No resolved URL - try direct loading as fallback
          self.loadArtworkDirect(track: track, completion: completion)
        }
      }
    }
  }

  /// Loads artwork directly from track's artwork URL without transform.
  private func loadArtworkDirect(track: Track, completion: @escaping @Sendable (UIImage?) -> Void) {
    guard let artworkUrl = track.artwork ?? track.artworkSource?.uri else {
      completion(nil)
      return
    }

    // Check for SF Symbol URI (e.g., "sf:heart.fill")
    if artworkUrl.hasPrefix("sf:") {
      let symbolName = String(artworkUrl.dropFirst(3))
      completion(sfSymbolImageForListItem(symbolName))
      return
    }

    guard let url = URL(string: artworkUrl) else {
      completion(nil)
      return
    }

    // Capture trait collection before async call
    let carTraitCollection = interfaceController.carTraitCollection
    let isSvg = url.pathExtension.lowercased() == "svg"
    let shouldTint = track.artworkCarPlayTinted ?? false

    // Add SVG processor if URL is an SVG
    var options: KingfisherOptionsInfo = []
    if isSvg {
      options.append(.processor(SVGProcessor(size: nil, scale: carTraitCollection.displayScale)))
    }

    KingfisherManager.shared.retrieveImage(with: url, options: options) { result in
      if case let .success(imageResult) = result {
        let image = imageResult.image

        if shouldTint {
          // Apply light/dark tinting for monochrome icons
          completion(self.createAdaptiveImage(image, carTraitCollection: carTraitCollection))
        } else {
          // Regular images (photos, album art) - show as-is
          completion(image)
        }
      } else {
        completion(nil)
      }
    }
  }

  /// Renders an image to a bitmap with the specified tint color (for monochrome icons).
  /// Thread-safe: UIGraphicsBeginImageContextWithOptions is safe to call from any thread (iOS 4+).
  private nonisolated func renderImageToBitmap(_ image: UIImage, tintColor: UIColor) -> UIImage {
    UIGraphicsBeginImageContextWithOptions(image.size, false, image.scale)
    defer { UIGraphicsEndImageContext() }

    tintColor.set()
    image.withRenderingMode(.alwaysTemplate).draw(in: CGRect(origin: .zero, size: image.size))

    guard let rendered = UIGraphicsGetImageFromCurrentImageContext() else {
      return image // Fallback to original if rendering fails
    }
    return rendered.withRenderingMode(.alwaysOriginal)
  }

  /// Creates light/dark tinted variants of an image and returns the appropriate one for current appearance
  private nonisolated func createAdaptiveImage(_ image: UIImage, carTraitCollection: UITraitCollection) -> UIImage {
    let lightImage = renderImageToBitmap(image, tintColor: .black)
    let darkImage = renderImageToBitmap(image, tintColor: .white)

    let asset = UIImageAsset()
    asset.register(lightImage, with: UITraitCollection(userInterfaceStyle: .light))
    asset.register(darkImage, with: UITraitCollection(userInterfaceStyle: .dark))

    return asset.image(with: carTraitCollection)
  }
}

// MARK: - Now Playing Observer

/// Private helper class for CPNowPlayingTemplateObserver conformance.
/// Kept separate from RNABCarPlayController to avoid exposing CarPlay protocols to Obj-C header.
private final class NowPlayingObserver: NSObject, CPNowPlayingTemplateObserver, @unchecked Sendable {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "NowPlayingObserver")
  private weak var controller: RNABCarPlayController?

  @MainActor
  init(controller: RNABCarPlayController) {
    self.controller = controller
    super.init()
  }

  func nowPlayingTemplateUpNextButtonTapped(_: CPNowPlayingTemplate) {
    Task { @MainActor in
      controller?.handleUpNextButtonTapped()
    }
  }

  func nowPlayingTemplateAlbumArtistButtonTapped(_: CPNowPlayingTemplate) {
    // Album/Artist button functionality - can be implemented later
    logger.debug("Album/Artist button tapped (not implemented)")
  }
}

// MARK: - Up Next Handler

private extension RNABCarPlayController {
  /// Handles the Up Next button tap from Now Playing screen
  func handleUpNextButtonTapped() {
    guard let player = audioBrowser?.getPlayer() else {
      logger.warning("Player not available for Up Next")
      return
    }

    let tracks = player.tracks

    guard !tracks.isEmpty else {
      logger.debug("No tracks in queue for Up Next")
      return
    }

    logger.info("Showing Up Next queue with \(tracks.count) tracks")

    let template = CPListTemplate(
      title: "Up Next",
      sections: [createUpNextSections(tracks: tracks, player: player)],
    )

    // Store reference for queue change updates
    upNextTemplate = template

    interfaceController.pushTemplate(template, animated: true, completion: nil)
  }

  /// Creates list items for the Up Next queue
  func createUpNextSections(tracks: [Track], player: TrackPlayer) -> CPListSection {
    let items = tracks.enumerated().map { index, track -> CPListItem in
      createListItem(for: track) { [weak self] _, completion in
        self?.logger.info("Skipping to track at index \(index): \(track.title)")
        do {
          try player.skipTo(index, playWhenReady: true)
        } catch {
          self?.logger.error("Failed to skip to track: \(error.localizedDescription)")
        }
        completion()
      }
    }
    return CPListSection(items: items)
  }

  /// Handles queue changes - updates Up Next list if visible
  @MainActor
  func handleQueueChanged(_ tracks: [Track]) {
    // Update the Up Next button enabled state
    updateNowPlayingUpNextButton()

    // Update the Up Next template if it's currently visible
    guard let template = upNextTemplate,
          let player = audioBrowser?.getPlayer()
    else {
      return
    }

    logger.debug("Queue changed, updating Up Next list with \(tracks.count) tracks")
    template.updateSections([createUpNextSections(tracks: tracks, player: player)])
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

private extension UIImage {
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
