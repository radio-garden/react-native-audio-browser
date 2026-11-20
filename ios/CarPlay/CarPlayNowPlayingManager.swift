import CarPlay
import Foundation
import os.log

@MainActor
final class CarPlayNowPlayingManager {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "CarPlayNowPlayingManager")

  private let interfaceController: CPInterfaceController
  private let audioBrowser: HybridAudioBrowser
  /// Read on each use: a re-setup replaces the bridge's player.
  private var player: Player? { audioBrowser.player }
  private let listItem: (Track, ((CPSelectableListItem, @escaping () -> Void) -> Void)?) -> CPListItem
  private let navigate: (_ path: String, _ title: String) -> Void

  private var subscriptions: [Subscription] = []
  private var nowPlayingObserver: NowPlayingObserver?
  private weak var upNextTemplate: CPListTemplate?
  /// A rebuild is skipped while this is unchanged: every `updateNowPlayingButtons` re-renders
  /// all buttons. Shuffle and repeat carry `isSelected` because CarPlay draws a re-render from
  /// the serialized state first: a button built unselected while its mode is on flashes off.
  private struct BuiltButtonRow: Equatable {
    let types: [CarPlayNowPlayingButton]
    let favorited: Bool?
    let shuffleSelected: Bool?
    let repeatSelected: Bool?
  }

  private var builtRow: BuiltButtonRow?

  /// An open list is rebuilt only when this changes: a rebuild reloads every row's artwork.
  private struct BuiltUpNext: Equatable {
    let queueVersion: Int
    let indices: [Int]
  }

  private var builtUpNext: BuiltUpNext?

  /// Resolved on track change, not on tap, so the album line shows only when a destination exists.
  private var albumArtistDestination: String?
  private let albumPathResolution = SupersedingTask<String, String>()
  /// The tapped track while its load runs, which wins over `currentTrack`: CarPlay reads
  /// `isAlbumArtistButtonEnabled` only when Now Playing is displayed, and that push comes first.
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
      browserManager.configChanged.subscribeOnMain { [weak self] _ in
        // A new config may bring a new `resolveAlbumPath`, so the current track is asked again.
        self?.albumPathResolution.cancel()
        self?.refresh()
      },
      audioBrowser.playerOptionsChangedEmitter.subscribeOnMain { [weak self] in self?.refresh() },
      browserManager.favoritesChanged.subscribeOnMain { [weak self] in self?.refreshButtons() },
      audioBrowser.favoriteChangedEmitter.subscribeOnMain { [weak self] _ in self?.refreshButtons() },
      audioBrowser.repeatModeChangedEmitter.subscribeOnMain { [weak self] _ in
        self?.refreshButtons()
        self?.refreshUpNext()
      },
      audioBrowser.shuffleChangedEmitter.subscribeOnMain { [weak self] _ in
        self?.refreshButtons()
        self?.refreshUpNext()
      },
      audioBrowser.queueChangedEmitter.subscribeOnMain { [weak self] _ in self?.refreshUpNext() },
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
    // A resolve in flight must not set the shared template for the next session.
    albumPathResolution.cancel()
  }

  func refresh() {
    refreshUpNext()
    refreshButtons()
    updateAlbumArtistDestination()
  }

  /// Call before pushing Now Playing for a just-tapped track: enabling the album line after
  /// the push renders no chevron.
  func prepare(for track: Track) {
    pendingAlbumArtistTrack = track
    updateAlbumArtistDestination()
  }

  // MARK: - Buttons

  private func refreshButtons() {
    // A gate hides the custom buttons; the transport controls stay, as a gate never blocks playback.
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
        // Pre-stamped: see BuiltButtonRow.
        shuffleButton.isSelected = row.shuffleSelected ?? false
        nowPlayingButtons.append(shuffleButton)

      case .repeat:
        let repeatButton = CPNowPlayingRepeatButton { [weak self] _ in
          self?.handleRepeatButtonTapped()
        }
        // Pre-stamped: see BuiltButtonRow.
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

  /// Pushes Now Playing, or pops back to it when it is buried under Up Next or a browse page,
  /// unless `popToFront` is false: a call seconds after the tap must not yank away a screen the
  /// user opened since.
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

  private func updateAlbumArtistDestination() {
    guard let track = pendingAlbumArtistTrack ?? player?.currentTrack else {
      logger.debug("AlbumArtist: no current track → disabled")
      settleAlbumArtistDestination(nil)
      return
    }
    logger.info("AlbumArtist: track=\(track.title) albumPath=\(track.albumPath ?? "nil") resolver=\(self.config.resolveAlbumPath != nil)")
    if let albumPath = track.albumPath {
      settleAlbumArtistDestination(albumPath)
      return
    }
    guard let resolver = config.resolveAlbumPath else {
      settleAlbumArtistDestination(nil)
      return
    }
    albumPathResolution.run(key: track.identity) {
      try await resolver(track).awaitCancellable()
    } deliver: { [weak self] result in
      guard let self else { return }
      if case let .failure(error) = result {
        logger.error("resolveAlbumPath failed: \(error.localizedDescription)")
      }
      setAlbumArtistDestination(try? result.get())
    }
  }

  /// Sets the destination without a resolve, ending any in flight so it cannot overwrite this.
  private func settleAlbumArtistDestination(_ path: String?) {
    albumPathResolution.cancel()
    setAlbumArtistDestination(path)
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

  // MARK: - Private - Button Handlers

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
    // The album line is what was tapped, so it titles the destination; artist may be unset.
    let title = track?.album ?? track?.artist ?? track?.title ?? ""
    logger.info("Album/Artist button tapped, navigating to \(destination)")
    navigate(destination, title)
  }

  private func refreshUpNext() {
    let indices = player?.upNextIndices ?? []
    let options = audioBrowser.playerOptions
    CPNowPlayingTemplate.shared.isUpNextButtonEnabled = NowPlayingButtonRules.upNextEnabled(
      option: options.carPlayUpNextButton,
      skipToQueueItem: options.capabilities.skipToQueueItem,
      upNextCount: indices.count,
    )
    guard let upNextTemplate else { return }
    let built = BuiltUpNext(queueVersion: player?.queueVersion ?? 0, indices: indices)
    guard built != builtUpNext else { return }
    builtUpNext = built
    upNextTemplate.updateSections([createUpNextSection(built)])
  }

  fileprivate func handleUpNextButtonTapped() {
    let indices = player?.upNextIndices ?? []
    guard !indices.isEmpty else {
      logger.debug("No tracks after the current one for Up Next")
      return
    }

    // System-localized, or what the app set.
    let upNextTitle = CPNowPlayingTemplate.shared.upNextTitle
    let built = BuiltUpNext(queueVersion: player?.queueVersion ?? 0, indices: indices)
    let template = CPListTemplate(
      title: upNextTitle.isEmpty ? "Up Next" : upNextTitle,
      sections: [createUpNextSection(built)],
    )

    upNextTemplate = template
    builtUpNext = built

    interfaceController.safePush(template, animated: true)
  }

  private func createUpNextSection(_ built: BuiltUpNext) -> CPListSection {
    let tracks = player?.tracks ?? []
    let maxItems = CPListTemplate.maximumItemCount
    if built.indices.count > maxItems {
      logger.info("Up Next has \(built.indices.count) tracks, clamping to CarPlay's limit of \(maxItems)")
    }
    let items = built.indices.prefix(maxItems).map { index -> CPListItem in
      let track = tracks[index]
      return listItem(track) { [weak self] _, completion in
        defer { completion() }
        guard let self else { return }
        // The list can outlive the capability: it stays pushed when the button turns off.
        switch NowPlayingButtonRules.queueItemPick(
          skipToQueueItem: audioBrowser.playerOptions.capabilities.skipToQueueItem,
          rowQueueVersion: built.queueVersion,
          queueVersion: player?.queueVersion,
        ) {
        case .skip:
          break
        case .refuse:
          return
        case .rebuild:
          logger.info("Up Next pick of \(track.title) came after a queue change; rebuilding the list")
          refreshUpNext()
          return
        }
        logger.info("Skipping to track at index \(index): \(track.title)")
        do {
          try player?.skipTo(index, playWhenReady: true)
        } catch {
          logger.error("Failed to skip to track: \(error.localizedDescription)")
        }
      }
    }
    return CPListSection(items: items)
  }
}

// MARK: - Now Playing Observer

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
