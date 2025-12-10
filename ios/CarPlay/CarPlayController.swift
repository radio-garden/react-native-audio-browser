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

  /// Helper object for CPNowPlayingTemplateObserver conformance
  /// (kept separate to avoid exposing CarPlay protocols to Obj-C header)
  private var nowPlayingObserver: NowPlayingObserver?

  /// Convenience accessor for browser config
  private var config: BrowserConfig {
    audioBrowser?.browserManager.config ?? BrowserConfig()
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

    // Subscribe to browser content changes
    setupContentSubscriptions()

    // Register as Now Playing observer for Up Next button
    setupNowPlayingTemplate()

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

    // Subscribe to config changes (for Now Playing buttons)
    audioBrowser.browserManager.onConfigChanged = { [weak self] _ in
      Task { @MainActor in
        self?.setupNowPlayingButtons()
      }
    }

    // Subscribe to favorite changes (for Now Playing button)
    let originalOnFavoriteChanged = audioBrowser.onFavoriteChanged
    audioBrowser.onFavoriteChanged = { [weak self, originalOnFavoriteChanged] event in
      originalOnFavoriteChanged(event)
      Task { @MainActor in
        self?.updateFavoriteButtonState(isFavorited: event.favorited)
      }
    }
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

    // Reserve one slot for search if configured
    let hasSearch = config.hasSearch
    let maxContentTabs = hasSearch ? maxTabs - 1 : maxTabs

    for tab in tabs.prefix(maxContentTabs) {
      let listTemplate = await createListTemplate(for: tab)
      tabTemplates.append(listTemplate)
    }

    let tabBar = CPTabBarTemplate(templates: tabTemplates)
    interfaceController.setRootTemplate(tabBar, animated: true, completion: nil)
  }

  // MARK: - List Templates

  @MainActor
  private func createListTemplate(for track: Track) async -> CPListTemplate {
    // Note: CPAssistantCellConfiguration requires INPlayMediaIntent handlers to be implemented.
    // Without SiriKit integration, it crashes with "clientAssistantCellUnavailableWithError".
    // See TODO.md for voice search implementation requirements.
    let template = CPListTemplate(
      title: track.title,
      sections: [],
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
    path: String,
  ) -> CPListTemplate {
    let template = CPListTemplate(
      title: resolvedTrack.title,
      sections: createSections(from: resolvedTrack),
    )

    template.userInfo = ["path": path] as [String: Any]

    return template
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

  private func createListItem(for track: Track) -> CPListItem {
    let item = CPListItem(
      text: track.title,
      detailText: track.subtitle ?? track.artist,
    )

    // Store track info for selection handling
    item.userInfo = [
      "url": track.url as Any,
      "src": track.src as Any,
      "hasSrc": track.src != nil,
      "hasUrl": track.url != nil,
    ]

    // Set accessory type based on whether track is browsable or playable
    if track.src != nil {
      // Playable track
      item.accessoryType = .none
      item.isPlaying = false // Will be updated based on current playback
    } else if track.url != nil {
      // Browsable only - show disclosure indicator
      item.accessoryType = .disclosureIndicator
    }

    // Load artwork asynchronously
    if let artworkUrl = track.artwork ?? track.artworkSource?.uri,
       let url = URL(string: artworkUrl)
    {
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

    // Check if this is a contextual URL (playable-only track with queue context)
    if let url = track.url, BrowserPathHelper.isContextual(url) {
      Task {
        do {
          // Expand the queue from the contextual URL
          if let expanded = try await audioBrowser.browserManager.expandQueueFromContextualUrl(url) {
            let (tracks, startIndex) = expanded

            await MainActor.run {
              // Replace queue and start at the selected track
              audioBrowser.getPlayer()?.setQueue(tracks, initialIndex: startIndex, playWhenReady: true)

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
    var commandsToEnable: [RemoteCommand] = []

    for buttonType in buttons {
      switch buttonType {
      case .shuffle:
        // Queue command to be enabled so button state syncs properly
        commandsToEnable.append(.changeShuffleMode)
        let shuffleButton = CPNowPlayingShuffleButton { [weak self] _ in
          self?.handleShuffleButtonTapped()
        }
        nowPlayingButtons.append(shuffleButton)

      case .repeat:
        // Queue command to be enabled so button state syncs properly
        commandsToEnable.append(.changeRepeatMode)
        let repeatButton = CPNowPlayingRepeatButton { [weak self] _ in
          self?.handleRepeatButtonTapped()
        }
        nowPlayingButtons.append(repeatButton)

      case .favorite:
        let favoriteButton = CPNowPlayingImageButton(
          image: favoriteButtonImage(isFavorited: false),
        ) { [weak self] _ in
          self?.handleFavoriteButtonTapped()
        }
        nowPlayingButtons.append(favoriteButton)

      case .playbackRate:
        // Queue command to be enabled with supported rates
        let rates = config.carPlayNowPlayingRates.map { NSNumber(value: $0) }
        commandsToEnable.append(.changePlaybackRate(supportedPlaybackRates: rates))
        let rateButton = CPNowPlayingPlaybackRateButton { [weak self] _ in
          self?.handlePlaybackRateButtonTapped()
        }
        nowPlayingButtons.append(rateButton)
      }
    }

    CPNowPlayingTemplate.shared.updateNowPlayingButtons(nowPlayingButtons)
    logger.info("Updated Now Playing with \(nowPlayingButtons.count) custom button(s)")

    // Enable the remote commands needed for CarPlay buttons to display state properly
    if !commandsToEnable.isEmpty, let player = audioBrowser?.getPlayer() {
      // Merge with existing commands to avoid disabling them
      var allCommands = player.remoteCommands
      for command in commandsToEnable {
        if !allCommands.contains(command) {
          allCommands.append(command)
        }
      }
      player.enableRemoteCommands(allCommands)

      // Sync current playback state so buttons display correctly
      player.updateNowPlayingPlaybackValues()
    }
  }

  /// Returns the appropriate image for the favorite button based on state
  private func favoriteButtonImage(isFavorited: Bool) -> UIImage {
    let symbolName = isFavorited ? "heart.fill" : "heart"
    return UIImage(systemName: symbolName) ?? UIImage()
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

  /// Handles playback rate button tap - shows a list of available rates
  private func handlePlaybackRateButtonTapped() {
    guard let player = audioBrowser?.getPlayer() else { return }

    let rates: [Double] = config.carPlayNowPlayingRates
    guard !rates.isEmpty else { return }

    let currentRate = Double(player.rate)

    // Create list items for each rate option
    let rateItems: [CPListItem] = rates.map { rate in
      let isSelected = (rate - currentRate).magnitude < 0.01
      let title = formatPlaybackRate(rate)
      let listItem = CPListItem(text: title, detailText: isSelected ? "Current" : nil)
      listItem.handler = { [weak self] _, completion in
        guard let self else {
          completion()
          return
        }
        player.rate = Float(rate)
        logger.info("CarPlay playback rate changed: \(currentRate) → \(rate)")
        interfaceController.popTemplate(animated: true) { _, _ in }
        completion()
      }
      return listItem
    }

    let rateTemplate = CPListTemplate(
      title: "Playback Speed",
      sections: [CPListSection(items: rateItems)]
    )
    interfaceController.pushTemplate(rateTemplate, animated: true, completion: nil)
  }

  /// Formats a playback rate for display (e.g., 1.0 -> "1x", 1.5 -> "1.5x")
  private func formatPlaybackRate(_ rate: Double) -> String {
    if rate == rate.rounded() {
      return "\(Int(rate))x"
    } else {
      return "\(rate)x"
    }
  }

  /// Updates the favorite button appearance based on current track's favorite state
  /// - Parameter isFavorited: If provided, uses this value; otherwise queries the active track
  private func updateFavoriteButtonState(isFavorited: Bool? = nil) {
    guard config.carPlayNowPlayingButtons.contains(.favorite) else { return }
    let favorited = isFavorited ?? (try? audioBrowser?.getActiveTrack())?.favorited ?? false
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
    let template = CPNowPlayingTemplate.shared

    // Up Next button: enabled if config allows and queue has more than 1 track
    if config.carPlayUpNextButton {
      let queueCount = audioBrowser?.getPlayer()?.tracks.count ?? 0
      template.isUpNextButtonEnabled = queueCount > 1
    } else {
      template.isUpNextButtonEnabled = false
    }
  }

  private func showNowPlaying() {
    // Update button states before showing
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
  private func handleContentChanged(_: ResolvedTrack?) {
    // Update the current template if it matches the content path
    // This is useful for refreshing content after favorites change, etc.
  }

  // MARK: - Error Handling

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

// MARK: - Now Playing Observer

/// Private helper class for CPNowPlayingTemplateObserver conformance.
/// Kept separate from RNABCarPlayController to avoid exposing CarPlay protocols to Obj-C header.
private final class NowPlayingObserver: NSObject, CPNowPlayingTemplateObserver {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "NowPlayingObserver")
  private weak var controller: RNABCarPlayController?

  init(controller: RNABCarPlayController) {
    self.controller = controller
    super.init()
  }

  func nowPlayingTemplateUpNextButtonTapped(_: CPNowPlayingTemplate) {
    controller?.handleUpNextButtonTapped()
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
    let currentIndex = player.currentIndex

    guard !tracks.isEmpty else {
      logger.debug("No tracks in queue for Up Next")
      return
    }

    logger.info("Showing Up Next queue with \(tracks.count) tracks")

    // Create list items for each track in the queue
    let items = tracks.enumerated().map { index, track -> CPListItem in
      let item = CPListItem(
        text: track.title,
        detailText: track.subtitle ?? track.artist,
      )

      // Mark currently playing track
      item.isPlaying = index == currentIndex

      // Load artwork asynchronously
      if let artworkUrl = track.artwork ?? track.artworkSource?.uri,
         let url = URL(string: artworkUrl)
      {
        Task { [weak self] in
          if let image = await self?.loadImage(from: url) {
            await MainActor.run {
              item.setImage(image)
            }
          }
        }
      }

      // Handler to skip to this track
      item.handler = { [weak self] _, completion in
        self?.logger.info("Skipping to track at index \(index): \(track.title)")
        do {
          try player.skipTo(index)
        } catch {
          self?.logger.error("Failed to skip to track: \(error.localizedDescription)")
        }
        completion()
      }

      return item
    }

    let template = CPListTemplate(
      title: "Up Next",
      sections: [CPListSection(items: items)],
    )

    interfaceController.pushTemplate(template, animated: true, completion: nil)
  }
}
