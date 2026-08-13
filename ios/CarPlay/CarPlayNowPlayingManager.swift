import CarPlay
import Foundation
import os.log

/// Manages the Now Playing template, buttons, Up Next, and related state for CarPlay.
///
/// Responsibilities:
/// - CPNowPlayingTemplate setup and button configuration
/// - Button action handlers (shuffle, repeat, favorite, playback rate)
/// - Button state updates (favorite appearance, Up Next enabled)
/// - Up Next template creation and queue change handling
/// - NowPlayingObserver for Up Next button taps
@MainActor
final class CarPlayNowPlayingManager {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "CarPlayNowPlayingManager")

  private let interfaceController: CPInterfaceController
  var listItemFactory: (Track, ((CPSelectableListItem, @escaping () -> Void) -> Void)?) -> CPListItem
  /// Pushes a browse destination (wired to the controller's navigateToPath).
  var navigateToPath: ((_ path: String, _ title: String) -> Void)?

  private weak var audioBrowser: HybridAudioBrowser?
  private var nowPlayingObserver: NowPlayingObserver?
  private weak var upNextTemplate: CPListTemplate?
  /// Everything the built button row renders — button types, the heart's
  /// favorited state, and the shuffle/repeat `isSelected` state. Rebuilds are
  /// skipped while this is unchanged (`onConfigChanged` fires on unrelated
  /// churn, and every `updateNowPlayingButtons` call re-renders ALL buttons).
  ///
  /// shuffle/repeat selected state is part of the snapshot because CarPlay
  /// SERIALIZES the buttons (NSSecureCoding) to its UI process: whenever the
  /// now-playing screen re-renders (it does on every metadata change), the
  /// stateful buttons first draw from their serialized `isSelected`, and only
  /// then overlay the live state the app reports via the command center. A
  /// button built with the default `isSelected = false` while repeat/shuffle
  /// is on therefore flashes background-off → background-on at every
  /// re-render. Stamping `isSelected` at build time (and rebuilding when the
  /// mode changes) makes the first-frame render match the live state.
  private struct BuiltButtonRow: Equatable {
    let types: [CarPlayNowPlayingButton]
    let favorited: Bool?
    let shuffleSelected: Bool?
    let repeatSelected: Bool?
  }

  private var builtRow: BuiltButtonRow?

  /// Browse path the album line navigates to for the active track —
  /// `track.albumPath`, or the pre-resolved `resolveAlbumPath` result. Resolved
  /// at track-change time (not at tap) so the album line only surfaces when a
  /// destination actually exists.
  private var albumArtistDestination: String?
  /// Drops a stale async `resolveAlbumPath` result when the track changed while
  /// the resolver was in flight (latest-update-wins).
  private var albumArtistGeneration: UInt = 0
  /// The just-tapped track whose load is still in flight. CarPlay reads
  /// `isAlbumArtistButtonEnabled` when the template is displayed (a change while
  /// visible doesn't re-render), and Now Playing is pushed at tap time — before
  /// the player's `currentTrack` reflects the tap — so the flag must be derived
  /// from the tapped track at push time. Cleared when the active track changes.
  private var pendingAlbumArtistTrack: Track?

  /// Convenience accessor for browser config
  private var config: BrowserConfig {
    audioBrowser?.browserManager.config ?? BrowserConfig()
  }

  /// The active track's favorited state, from the authoritative favorite set
  /// (see `HybridAudioBrowser.isActiveTrackFavorited`).
  private var isActiveTrackFavorited: Bool {
    audioBrowser?.isActiveTrackFavorited() ?? false
  }

  init(interfaceController: CPInterfaceController) {
    self.interfaceController = interfaceController
    self.listItemFactory = { track, _ in CPListItem(text: track.title, detailText: nil) }
  }

  // MARK: - Lifecycle

  func setup(audioBrowser: HybridAudioBrowser) {
    self.audioBrowser = audioBrowser
    setupNowPlayingTemplate()
  }

  func teardown() {
    if let observer = nowPlayingObserver {
      CPNowPlayingTemplate.shared.remove(observer)
      nowPlayingObserver = nil
    }
    pendingAlbumArtistTrack = nil
    audioBrowser = nil
  }

  // MARK: - Now Playing Template

  private func setupNowPlayingTemplate() {
    let template = CPNowPlayingTemplate.shared

    let observer = NowPlayingObserver(manager: self)
    nowPlayingObserver = observer
    template.add(observer)

    setupNowPlayingButtons()
    updateNowPlayingButtonStates()
  }

  /// Sets up custom Now Playing buttons based on configuration and current
  /// player state, skipping the rebuild when nothing the row renders changed.
  func setupNowPlayingButtons() {
    // While a gate is active, custom buttons (favorite etc.) are hidden;
    // the system transport controls stay — a gate never blocks playback.
    let configured = (audioBrowser?.isGateActive ?? false) ? [] : (audioBrowser?.carPlayNowPlayingButtons ?? [])
    // The template caps custom buttons at 5 (CPNowPlayingTemplate.h) and says
    // nothing about overflow — unlike lists (trim) or the tab bar (throw). The
    // config comes straight from JS, where duplicates and excess are
    // representable, so dedupe (order preserved) and clamp here.
    var deduped: [CarPlayNowPlayingButton] = []
    for button in configured where !deduped.contains(button) {
      deduped.append(button)
    }
    let buttons = Array(deduped.prefix(5))
    let player = audioBrowser?.getPlayer()
    let row = BuiltButtonRow(
      types: buttons,
      favorited: buttons.contains(.favorite) ? isActiveTrackFavorited : nil,
      shuffleSelected: buttons.contains(.shuffle) ? (player?.shuffleEnabled ?? false) : nil,
      repeatSelected: buttons.contains(.repeat) ? (player?.getRepeatMode() ?? .off) != .off : nil,
    )
    guard row != builtRow else { return }
    builtRow = row
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
        // Pre-stamp the serialized selected state — see BuiltButtonRow.
        shuffleButton.isSelected = row.shuffleSelected ?? false
        nowPlayingButtons.append(shuffleButton)

      case .repeat:
        let repeatButton = CPNowPlayingRepeatButton { [weak self] _ in
          self?.handleRepeatButtonTapped()
        }
        // Pre-stamp the serialized selected state — see BuiltButtonRow.
        repeatButton.isSelected = row.repeatSelected ?? false
        nowPlayingButtons.append(repeatButton)

      case .favorite:
        let favoriteButton = CPNowPlayingImageButton(
          image: favoriteButtonImage(isFavorited: row.favorited ?? false),
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

  // MARK: - Show Now Playing

  /// `popToFront` controls what happens when Now Playing is already on the
  /// stack but buried under templates pushed on top of it (Up Next, the album
  /// line's browse destination): a user-initiated surface (tap, voice play)
  /// pops back to it, while the deferred post-resolve calls in
  /// `handleItemSelection` pass `false` — they run seconds after the tap, and
  /// popping then would yank away a screen the user navigated to meanwhile.
  func showNowPlaying(popToFront: Bool = true) {
    updateNowPlayingButtonStates()
    let nowPlayingTemplate = CPNowPlayingTemplate.shared
    if interfaceController.templates.contains(where: { $0 === nowPlayingTemplate }) {
      if popToFront {
        interfaceController.safePop(to: nowPlayingTemplate, animated: true)
      }
    } else {
      interfaceController.safePush(nowPlayingTemplate, animated: true)
    }
  }

  // MARK: - Button State Updates

  /// Refreshes the row for a favorite/shuffle/repeat state change — the
  /// snapshot diff in setupNowPlayingButtons makes this a no-op when nothing
  /// the row renders actually changed.
  func updateFavoriteButtonState() {
    setupNowPlayingButtons()
  }

  /// Updates Now Playing button states based on config and current queue
  func updateNowPlayingButtonStates() {
    updateNowPlayingUpNextButton()
    setupNowPlayingButtons()
    updateAlbumArtistButtonState()
  }

  /// Re-resolves the album line's destination for the active track and
  /// enables the (tappable) album/artist button only when one exists:
  /// `track.albumPath`, or the app's `resolveAlbumPath` result.
  /// Marks `track` as the just-tapped (still loading) track and derives the
  /// album line's destination from it. Must run before the Now Playing
  /// template is pushed — CarPlay reads `isAlbumArtistButtonEnabled` at display
  /// time, so enabling after the push (when the load lands) renders no chevron.
  func prepareAlbumArtistButton(for track: Track) {
    pendingAlbumArtistTrack = track
    updateAlbumArtistButtonState()
  }

  /// Clears the tap-time pending track (the player's `currentTrack` is now
  /// authoritative) and refreshes all button states.
  func handleActiveTrackChanged() {
    pendingAlbumArtistTrack = nil
    updateNowPlayingButtonStates()
  }

  func updateAlbumArtistButtonState() {
    albumArtistGeneration &+= 1
    let generation = albumArtistGeneration

    // The just-tapped track wins while its load is in flight: currentTrack
    // still points at the previous track (or nil) at push time.
    guard let track = pendingAlbumArtistTrack ?? audioBrowser?.getPlayer()?.currentTrack else {
      logger.debug("AlbumArtist: no current track → disabled")
      setAlbumArtistDestination(nil)
      return
    }
    logger.info("AlbumArtist: track=\(track.title) albumPath=\(track.albumPath ?? "nil") resolver=\(self.config.resolveAlbumPath != nil)")
    if let albumPath = track.albumPath {
      setAlbumArtistDestination(albumPath)
      return
    }
    guard let resolver = config.resolveAlbumPath else {
      setAlbumArtistDestination(nil)
      return
    }
    Task { @MainActor [weak self] in
      let path: String?
      do {
        path = try await resolver(track).await()
      } catch {
        self?.logger.error("resolveAlbumPath failed: \(error.localizedDescription)")
        path = nil
      }
      guard let self, self.albumArtistGeneration == generation else { return }
      self.setAlbumArtistDestination(path)
    }
  }

  private func setAlbumArtistDestination(_ path: String?) {
    albumArtistDestination = path
    let template = CPNowPlayingTemplate.shared
    let enabled = path != nil
    if template.isAlbumArtistButtonEnabled != enabled {
      logger.info("AlbumArtist: button \(enabled ? "enabled" : "disabled") (destination: \(path ?? "nil"))")
      template.isAlbumArtistButtonEnabled = enabled
    }
  }

  // MARK: - Queue Changes

  /// Handles queue changes - updates Up Next list if visible
  func handleQueueChanged(_ tracks: [Track]) {
    updateNowPlayingUpNextButton()

    guard let template = upNextTemplate,
          let player = audioBrowser?.getPlayer()
    else {
      return
    }

    logger.debug("Queue changed, updating Up Next list with \(tracks.count) tracks")
    template.updateSections([createUpNextSection(tracks: tracks, player: player)])
  }

  // MARK: - Private - Button Handlers

  /// Returns the appropriate image for the favorite button based on state
  private func favoriteButtonImage(isFavorited: Bool) -> UIImage {
    let symbolName = isFavorited ? "heart.fill" : "heart"
    guard let image = UIImage(systemName: symbolName)?.resized(
      to: CPNowPlayingButtonMaximumImageSize,
      scale: interfaceController.carTraitCollection.displayScale,
    ) else {
      return UIImage()
    }
    return image
  }

  private func handleShuffleButtonTapped() {
    guard let player = audioBrowser?.getPlayer() else { return }

    let newEnabled = !player.shuffleEnabled
    player.shuffleEnabled = newEnabled
    logger.info("CarPlay shuffle mode changed: \(newEnabled)")
  }

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

  private func handleFavoriteButtonTapped() {
    try? audioBrowser?.toggleActiveTrackFavorited()
    logger.info("CarPlay favorite toggled")
  }

  private func handlePlaybackRateButtonTapped() {
    guard let audioBrowser, let player = audioBrowser.getPlayer() else { return }

    let rates = audioBrowser.playbackRates
    guard !rates.isEmpty else { return }

    let currentRate = Double(player.rate)
    let nextRate: Double

    if let currentIndex = rates.firstIndex(where: { (currentRate - $0).magnitude < 0.01 }) {
      let nextIndex = (currentIndex + 1) % rates.count
      nextRate = rates[nextIndex]
    } else {
      nextRate = rates.first { $0 > currentRate } ?? rates[0]
    }

    player.rate = Float(nextRate)
    logger.info("CarPlay playback rate changed: \(currentRate) → \(nextRate)")
  }

  // MARK: - Private - Up Next

  fileprivate func handleAlbumArtistButtonTapped() {
    guard let destination = albumArtistDestination else { return }
    let track = pendingAlbumArtistTrack ?? audioBrowser?.getPlayer()?.currentTrack
    // The album line is what was tapped, so its text is the natural title for
    // the pushed destination (artist may be unset, or the live song).
    let title = track?.album ?? track?.artist ?? track?.title ?? ""
    logger.info("Album/Artist button tapped, navigating to \(destination)")
    navigateToPath?(destination, title)
  }

  private func updateNowPlayingUpNextButton() {
    let template = CPNowPlayingTemplate.shared
    template.isUpNextButtonEnabled = (audioBrowser?.carPlayUpNextButton ?? true) && (audioBrowser?.getPlayer()?.tracks.count ?? 0) > 1
  }

  fileprivate func handleUpNextButtonTapped() {
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

    // Reuse the now-playing template's button title (system-localized "Up Next"
    // by default, and whatever the app set otherwise) instead of hardcoding an
    // un-localized string.
    let upNextTitle = CPNowPlayingTemplate.shared.upNextTitle
    let template = CPListTemplate(
      title: upNextTitle.isEmpty ? "Up Next" : upNextTitle,
      sections: [createUpNextSection(tracks: tracks, player: player)],
    )

    upNextTemplate = template

    interfaceController.safePush(template, animated: true)
  }

  private func createUpNextSection(tracks: [Track], player: TrackPlayer) -> CPListSection {
    // Clamp to CarPlay's list limit (createSections does the same for browse
    // lists). Taking the prefix keeps item positions aligned with queue indices.
    let maxItems = CPListTemplate.maximumItemCount
    if tracks.count > maxItems {
      logger.info("Up Next queue has \(tracks.count) tracks, clamping to CarPlay's limit of \(maxItems)")
    }
    let items = tracks.prefix(maxItems).enumerated().map { index, track -> CPListItem in
      listItemFactory(track) { [weak self] _, completion in
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
}

// MARK: - Now Playing Observer

/// Private helper class for CPNowPlayingTemplateObserver conformance.
private final class NowPlayingObserver: NSObject, CPNowPlayingTemplateObserver, @unchecked Sendable {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "NowPlayingObserver")
  private weak var manager: CarPlayNowPlayingManager?

  @MainActor
  init(manager: CarPlayNowPlayingManager) {
    self.manager = manager
    super.init()
  }

  func nowPlayingTemplateUpNextButtonTapped(_: CPNowPlayingTemplate) {
    Task { @MainActor in
      manager?.handleUpNextButtonTapped()
    }
  }

  func nowPlayingTemplateAlbumArtistButtonTapped(_: CPNowPlayingTemplate) {
    Task { @MainActor in
      manager?.handleAlbumArtistButtonTapped()
    }
  }
}
