import CarPlay
import Foundation
import os.log

/// The CarPlay Now Playing template for one session: its custom buttons, the album line's
/// destination, Up Next, and the events that refresh them.
@MainActor
final class CarPlayNowPlayingManager {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "CarPlayNowPlayingManager")

  private let interfaceController: CPInterfaceController
  private let audioBrowser: HybridAudioBrowser
  /// Read on each use: a re-setup replaces the bridge's player.
  private var player: Player? { audioBrowser.player }
  private let listItem: (Track, ((CPSelectableListItem, @escaping () -> Void) -> Void)?) -> CPListItem
  /// Pushes a browse destination: the album line's.
  private let navigate: (_ path: String, _ title: String) -> Void

  private var subscriptions: [Subscription] = []
  private var nowPlayingObserver: NowPlayingObserver?
  private weak var upNextTemplate: CPListTemplate?
  /// Everything the built button row renders — button types, the heart's
  /// favorited state, and the shuffle/repeat `isSelected` state. Rebuilds are
  /// skipped while this is unchanged (`configChanged` fires on unrelated
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

  private var config: BrowserConfig {
    audioBrowser.browserManager.config
  }

  init(
    interfaceController: CPInterfaceController,
    audioBrowser: HybridAudioBrowser,
    listItem: @escaping (Track, ((CPSelectableListItem, @escaping () -> Void) -> Void)?) -> CPListItem,
    navigate: @escaping (_ path: String, _ title: String) -> Void,
  ) {
    self.interfaceController = interfaceController
    self.audioBrowser = audioBrowser
    self.listItem = listItem
    self.navigate = navigate
  }

  // MARK: - Lifecycle

  func start() {
    let observer = NowPlayingObserver(manager: self)
    nowPlayingObserver = observer
    CPNowPlayingTemplate.shared.add(observer)

    let browserManager = audioBrowser.browserManager
    subscriptions = [
      browserManager.configChanged.subscribeOnMain { [weak self] _ in self?.refresh() },
      audioBrowser.playerOptionsChangedEmitter.subscribeOnMain { [weak self] in self?.refresh() },
      browserManager.favoritesChanged.subscribeOnMain { [weak self] in self?.refreshButtons() },
      audioBrowser.favoriteChangedEmitter.subscribeOnMain { [weak self] _ in self?.refreshButtons() },
      audioBrowser.repeatModeChangedEmitter.subscribeOnMain { [weak self] _ in self?.refreshButtons() },
      audioBrowser.shuffleChangedEmitter.subscribeOnMain { [weak self] _ in self?.refreshButtons() },
      audioBrowser.queueChangedEmitter.subscribeOnMain { [weak self] in self?.queueChanged($0) },
      audioBrowser.showNowPlayingRequestedEmitter.subscribeOnMain { [weak self] in self?.showNowPlaying() },
      audioBrowser.activeTrackChangedEmitter.subscribeOnMain { [weak self] _ in
        // The player's current track is authoritative again.
        self?.pendingAlbumArtistTrack = nil
        self?.refresh()
      },
    ]
    refresh()
  }

  func end() {
    if let nowPlayingObserver { CPNowPlayingTemplate.shared.remove(nowPlayingObserver) }
    nowPlayingObserver = nil
    subscriptions = []
    pendingAlbumArtistTrack = nil
    // A resolve still in flight must not set the shared template for the next session.
    albumArtistGeneration &+= 1
  }

  /// Re-renders Up Next availability, the custom buttons and the album line's destination.
  func refresh() {
    updateUpNextButton()
    refreshButtons()
    updateAlbumArtistDestination()
  }

  /// Marks `track` as the just-tapped (still loading) track and derives the album line's
  /// destination from it. Call before pushing Now Playing: CarPlay reads
  /// `isAlbumArtistButtonEnabled` at display time, so enabling it after the push renders no
  /// chevron.
  func prepare(for track: Track) {
    pendingAlbumArtistTrack = track
    updateAlbumArtistDestination()
  }

  // MARK: - Buttons

  /// Builds the custom button row from the configuration and player state, skipping the
  /// rebuild when nothing the row renders changed.
  private func refreshButtons() {
    // A gate hides the custom buttons; the transport controls stay, as a gate never blocks
    // playback.
    let buttons = NowPlayingButtonRules.buttons(
      audioBrowser.browseGate.isActive ? [] : audioBrowser.playerOptions.carPlayNowPlayingButtons,
    )
    let row = BuiltButtonRow(
      types: buttons,
      favorited: buttons.contains(.favorite) ? audioBrowser.isActiveTrackFavorited() : nil,
      shuffleSelected: buttons.contains(.shuffle) ? player?.shuffleEnabled ?? false : nil,
      repeatSelected: buttons.contains(.repeat) ? (player?.repeatMode ?? .off) != .off : nil,
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
  /// `CarPlaySession.select` pass `false` — they run seconds after the tap, and
  /// popping then would yank away a screen the user navigated to meanwhile.
  func showNowPlaying(popToFront: Bool = true) {
    refresh()
    let nowPlayingTemplate = CPNowPlayingTemplate.shared
    if interfaceController.templates.contains(where: { $0 === nowPlayingTemplate }) {
      if popToFront {
        interfaceController.safePop(to: nowPlayingTemplate, animated: true)
      }
    } else {
      interfaceController.safePush(nowPlayingTemplate, animated: true)
    }
  }

  // MARK: - Album Line

  /// Resolves the album line's destination for the active (or just-tapped) track and enables
  /// the album/artist button only when one exists: `track.albumPath`, or the app's
  /// `resolveAlbumPath` result.
  private func updateAlbumArtistDestination() {
    albumArtistGeneration &+= 1
    let generation = albumArtistGeneration

    // The just-tapped track wins while its load is in flight: currentTrack
    // still points at the previous track (or nil) at push time.
    guard let track = pendingAlbumArtistTrack ?? player?.currentTrack else {
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

  private func queueChanged(_ tracks: [Track]) {
    updateUpNextButton()
    guard let upNextTemplate else { return }
    upNextTemplate.updateSections([createUpNextSection(tracks: tracks)])
  }

  // MARK: - Private - Button Handlers

  /// Returns the appropriate image for the favorite button based on state
  private func favoriteButtonImage(isFavorited: Bool) -> UIImage {
    let symbolName = isFavorited ? "heart.fill" : "heart"
    guard let image = UIImage(systemName: symbolName)?.fitted(
      to: CPNowPlayingButtonMaximumImageSize,
      scale: interfaceController.carTraitCollection.displayScale,
    ) else {
      return UIImage()
    }
    return image
  }

  private func handleShuffleButtonTapped() {
    guard let player else { return }
    let newEnabled = !player.shuffleEnabled
    player.shuffleEnabled = newEnabled
    logger.info("CarPlay shuffle mode changed: \(newEnabled)")
  }

  private func handleRepeatButtonTapped() {
    guard let player else { return }
    let currentMode = player.repeatMode
    let newMode = NowPlayingButtonRules.repeatMode(after: currentMode)
    player.repeatMode = newMode
    logger.info("CarPlay repeat mode changed: \(currentMode.stringValue) → \(newMode.stringValue)")
  }

  private func handleFavoriteButtonTapped() {
    do {
      try audioBrowser.toggleActiveTrackFavorited()
    } catch {
      logger.error("favorite toggle failed: \(error.localizedDescription)")
    }
    logger.info("CarPlay favorite toggled")
  }

  private func handlePlaybackRateButtonTapped() {
    guard let player else { return }
    let currentRate = Double(player.rate)
    guard let nextRate = NowPlayingButtonRules.rate(after: currentRate, in: audioBrowser.playerOptions.playbackRates) else { return }
    player.rate = Float(nextRate)
    logger.info("CarPlay playback rate changed: \(currentRate) → \(nextRate)")
  }

  // MARK: - Private - Up Next

  fileprivate func handleAlbumArtistButtonTapped() {
    guard let destination = albumArtistDestination else { return }
    let track = pendingAlbumArtistTrack ?? player?.currentTrack
    // The album line is what was tapped, so its text is the natural title for
    // the pushed destination (artist may be unset, or the live song).
    let title = track?.album ?? track?.artist ?? track?.title ?? ""
    logger.info("Album/Artist button tapped, navigating to \(destination)")
    navigate(destination, title)
  }

  private func updateUpNextButton() {
    CPNowPlayingTemplate.shared.isUpNextButtonEnabled = audioBrowser.playerOptions.carPlayUpNextButton && (player?.tracks.count ?? 0) > 1
  }

  fileprivate func handleUpNextButtonTapped() {
    let tracks = player?.tracks ?? []

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
      sections: [createUpNextSection(tracks: tracks)],
    )

    upNextTemplate = template

    interfaceController.safePush(template, animated: true)
  }

  private func createUpNextSection(tracks: [Track]) -> CPListSection {
    // Clamp to CarPlay's list limit (createSections does the same for browse
    // lists). Taking the prefix keeps item positions aligned with queue indices.
    let maxItems = CPListTemplate.maximumItemCount
    if tracks.count > maxItems {
      logger.info("Up Next queue has \(tracks.count) tracks, clamping to CarPlay's limit of \(maxItems)")
    }
    let items = tracks.prefix(maxItems).enumerated().map { index, track -> CPListItem in
      listItem(track) { [weak self] _, completion in
        self?.logger.info("Skipping to track at index \(index): \(track.title)")
        do {
          try self?.player?.skipTo(index, playWhenReady: true)
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

private extension UIImage {
  /// Draws the image centered in `targetSize`, keeping its aspect ratio, at the car screen's
  /// display scale.
  func fitted(to targetSize: CGSize, scale: CGFloat) -> UIImage? {
    UIGraphicsBeginImageContextWithOptions(targetSize, false, scale)
    defer { UIGraphicsEndImageContext() }
    let fitScale = min(targetSize.width / size.width, targetSize.height / size.height)
    let scaledSize = CGSize(width: size.width * fitScale, height: size.height * fitScale)
    let origin = CGPoint(x: (targetSize.width - scaledSize.width) / 2, y: (targetSize.height - scaledSize.height) / 2)
    draw(in: CGRect(origin: origin, size: scaledSize))
    return UIGraphicsGetImageFromCurrentImageContext()?.withRenderingMode(.alwaysTemplate)
  }
}
