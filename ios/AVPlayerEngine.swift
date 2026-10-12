@preconcurrency import AVFoundation
import Foundation
import MediaPlayer
import NitroModules
import os.log

/// The `PlayerEngine` over AVFoundation: the AVPlayer and its observers, media loading, the
/// now-playing session and the remote commands. Carries out what the `Player` decides; holds
/// no playback policy.
@MainActor
final class AVPlayerEngine: PlayerEngine {
  let logger = Logger(subsystem: "com.audiobrowser", category: "AVPlayerEngine")

  var onEvent: (@MainActor (EngineEvent) -> Void)?
  private weak var callbacks: AVPlayerEngineCallbacks?

  let nowPlayingInfoController: NowPlayingInfoController
  let nowPlayingUpdater: NowPlayingUpdater
  let remoteCommandController: RemoteCommandController
  private let mediaLoader = MediaLoader()
  private let silentAudioPrimer = SilentAudioPrimer()

  private var avPlayer = AVPlayer()
  private var playerObserver: PlayerObserver?
  private var itemObserver: ItemObserver?
  private var rate: Float = 1
  private var skipAvailability = (canNext: false, canPrevious: false)

  /// Applied to each item as it loads.
  var audioTimePitchAlgorithm: AVAudioTimePitchAlgorithm = .timeDomain

  var remoteCommands: [RemoteCommand] = [] {
    didSet { remoteCommandController.enable(commands: remoteCommands) }
  }

  /// - Parameter resolver: Resolves media URLs and now-playing artwork.
  init(
    callbacks: AVPlayerEngineCallbacks?,
    resolver: MediaResolving?,
    nowPlayingInfoController: NowPlayingInfoController = NowPlayingInfoController(),
  ) {
    self.callbacks = callbacks
    self.nowPlayingInfoController = nowPlayingInfoController
    nowPlayingUpdater = NowPlayingUpdater(nowPlayingInfoController: nowPlayingInfoController)
    remoteCommandController = RemoteCommandController(callbacks: callbacks)

    mediaLoader.onEvent = { [weak self] in self?.mediaLoaderDidEmit($0) }
    mediaLoader.mediaUrlResolver = { [weak resolver] src, track in
      await resolver?.resolveMediaUrl(src, track: track) ?? MediaResolvedUrl(url: src, headers: nil, userAgent: nil)
    }
    nowPlayingUpdater.artworkUrlResolver = { [weak resolver] track, imageContext in
      await resolver?.resolveNowPlayingArtwork(track: track, imageContext: imageContext)
    }
    nowPlayingUpdater.onChanged = { [weak self] in self?.callbacks?.playerDidChangeNowPlaying($0) }
    remoteCommandController.skipAvailability = { [weak self] in
      self?.skipAvailability ?? (false, false)
    }
    setUpAVPlayer()
  }

  /// Releases the player for good: the now-playing session, the observers and the remote
  /// commands go with it.
  func destroy() {
    remoteCommandController.switchCommandCenter(nowPlayingInfoController.unlinkPlayer())
    playerObserver?.invalidate()
    playerObserver = nil
    stopObservingItem()
    remoteCommandController.disableAll()
  }

  /// Re-requests the now-playing election, which the system may have declined at link time while
  /// the audio session was still inactive.
  func reactivateNowPlayingSession() {
    nowPlayingInfoController.reactivateSessionIfNeeded()
  }

  // MARK: - Buffering

  var bufferDuration: Double = 0 {
    didSet {
      avPlayer.automaticallyWaitsToMinimizeStalling = bufferDuration == 0
      mediaLoader.bufferDuration = bufferDuration
    }
  }

  var automaticallyWaitsToMinimizeStalling: Bool {
    get { avPlayer.automaticallyWaitsToMinimizeStalling }
    set {
      if newValue { bufferDuration = 0 }
      avPlayer.automaticallyWaitsToMinimizeStalling = newValue
    }
  }

  var isMuted: Bool {
    get { avPlayer.isMuted }
    set { avPlayer.isMuted = newValue }
  }

  // MARK: - The Current Item

  var hasItem: Bool {
    avPlayer.currentItem != nil
  }

  var hasLoadedAsset: Bool {
    mediaLoader.asset != nil
  }

  var currentTime: Double {
    let seconds = avPlayer.currentTime().seconds
    return seconds.isNaN ? 0 : seconds
  }

  /// The current item's duration, or 0 when it has none (a live stream). Not
  /// `item.asset.duration`: deprecated since iOS 16, and it blocks the main actor fetching a
  /// remote asset's duration.
  var duration: Double {
    guard let item = avPlayer.currentItem else { return 0 }
    if !item.duration.seconds.isNaN {
      return item.duration.seconds
    }
    if let seekable = item.seekableTimeRanges.last?.timeRangeValue.duration.seconds, !seekable.isNaN {
      return seekable
    }
    return 0
  }

  var bufferedPosition: Double {
    avPlayer.currentItem?.loadedTimeRanges.last?.timeRangeValue.end.seconds ?? 0
  }

  var resumePosition: Double? {
    guard let item = avPlayer.currentItem, !item.duration.isIndefinite else { return nil }
    return item.currentTime().seconds
  }

  var liveEdge: Double? {
    guard let range = avPlayer.currentItem?.seekableTimeRanges.last?.timeRangeValue,
          range.duration.seconds > 0
    else { return nil }
    return range.end.seconds
  }

  var isPlaybackLikelyToKeepUp: Bool {
    avPlayer.currentItem?.isPlaybackLikelyToKeepUp ?? false
  }

  var isAirPlayRoute: Bool {
    AVAudioSession.sharedInstance().currentRoute.outputs.contains { $0.portType == .airPlay }
  }

  var volume: Float {
    get { avPlayer.volume }
    set { avPlayer.volume = newValue }
  }

  // MARK: - Loading

  func resolveAndLoad(src: String, track: Track) {
    mediaLoader.resolveAndLoad(src: src, track: track)
  }

  func loadResolved() {
    mediaLoader.loadAsset()
  }

  func cancelLoading() {
    mediaLoader.cancelAll()
  }

  func dropAsset() {
    stopObservingItem()
    mediaLoader.clearAsset()
  }

  func clearItem() {
    dropAsset()
    avPlayer.replaceCurrentItem(with: nil)
  }

  func stopObservingItem() {
    itemObserver?.invalidate()
    itemObserver = nil
  }

  func recreate() {
    playerObserver?.invalidate()
    remoteCommandController.switchCommandCenter(nowPlayingInfoController.unlinkPlayer())
    avPlayer = AVPlayer()
    setUpAVPlayer()
  }

  private func setUpAVPlayer() {
    avPlayer.allowsExternalPlayback = false
    avPlayer.automaticallyWaitsToMinimizeStalling = bufferDuration == 0
    playerObserver = PlayerObserver(player: avPlayer) { [weak self] in self?.playerDidEmit($0) }
    remoteCommandController.switchCommandCenter(nowPlayingInfoController.linkPlayer(avPlayer))
    avPlayer.defaultRate = rate
  }

  // MARK: - Transport

  func play() {
    avPlayer.play()
    if rate != 1 { avPlayer.rate = rate }
  }

  func pause() {
    avPlayer.pause()
  }

  func setRate(_ rate: Float, playing: Bool) {
    self.rate = rate
    avPlayer.defaultRate = rate
    if playing { avPlayer.rate = rate }
  }

  func seek(to seconds: Double, completion: @escaping @MainActor (Bool) -> Void) {
    avPlayer.seek(
      to: CMTime(seconds: seconds, preferredTimescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero,
    ) { finished in
      Task { @MainActor in completion(finished) }
    }
  }

  func applyTimePitchAlgorithm() {
    avPlayer.currentItem?.audioTimePitchAlgorithm = audioTimePitchAlgorithm
  }

  // MARK: - Now Playing and Remote Commands

  func primeNowPlaying() {
    silentAudioPrimer.prime()
  }

  func loadNowPlayingMetadata(for track: Track) {
    nowPlayingUpdater.loadMetaValues(for: track)
  }

  func clearNowPlaying() {
    nowPlayingUpdater.clearNowPlaying()
  }

  func updateRemoteRepeatMode(_ mode: RepeatMode) {
    remoteCommandController.updateRepeatMode(mode)
  }

  func updateRemoteShuffleMode(_ enabled: Bool) {
    remoteCommandController.updateShuffleMode(enabled)
  }

  func updateSkipAvailability(canNext: Bool, canPrevious: Bool) {
    skipAvailability = (canNext, canPrevious)
    remoteCommandController.refreshSkipAvailability()
  }

  // MARK: - Observed Events

  private func playerDidEmit(_ event: PlayerObserver.Event) {
    switch event {
    case let .status(status):
      if status == .failed { reportFailure(nil) }
    case let .timeControlStatus(status):
      let mapped: PlayerTimeControlStatus
      switch status {
      case .paused: mapped = .paused
      case .waitingToPlayAtSpecifiedRate: mapped = .waitingToPlayAtSpecifiedRate
      case .playing: mapped = .playing
      @unknown default: return
      }
      onEvent?(.timeControlStatus(mapped, waitingForNoItem: avPlayer.reasonForWaitingToPlay == .noItemToPlay))
    case .audioDidStart:
      onEvent?(.audioDidStart)
    }
  }

  private func itemDidEmit(_ event: ItemObserver.Event) {
    switch event {
    case let .likelyToKeepUp(isLikely): onEvent?(.likelyToKeepUp(isLikely))
    case let .failed(error), let .failedToPlayToEnd(error): reportFailure(error)
    case .playedToEnd: onEvent?(.playedToEnd)
    case .stalled: onEvent?(.stalled)
    case let .timedMetadata(groups): callbacks?.playerDidReceiveTimedMetadata(groups)
    }
  }

  /// The item's own error stands in when the report carried none.
  private func reportFailure(_ error: Error?) {
    onEvent?(.failed(error ?? avPlayer.currentItem?.error, httpStatusCode: currentItemHttpStatusCode))
  }

  /// The HTTP status the server last answered the current item with. `AVPlayerItem.error` never
  /// carries it (a 404 arrives as an opaque CoreMedia error), so the per-item error log is the
  /// only place it surfaces.
  private var currentItemHttpStatusCode: Int? {
    guard let events = avPlayer.currentItem?.errorLog()?.events else { return nil }
    return PlaybackFailure.httpStatusCode(fromErrorStatusCodes: events.map(\.errorStatusCode))
  }

  private func mediaLoaderDidEmit(_ event: MediaLoader.Event) {
    switch event {
    case let .prepared(item):
      nowPlayingInfoController.prepareItem(item)
      avPlayer.replaceCurrentItem(with: item)
      itemObserver?.invalidate()
      itemObserver = ItemObserver(item: item) { [weak self] in self?.itemDidEmit($0) }
      onEvent?(.itemPrepared(likelyToKeepUp: item.isPlaybackLikelyToKeepUp))
    case let .failed(error):
      attachPublishingCarrierItemIfNeeded()
      onEvent?(.loadFailed(error))
    case .unplayable:
      attachPublishingCarrierItemIfNeeded()
      onEvent?(.unplayable)
    case let .invalidSource(url):
      // No carrier: an invalid src never produced an asset.
      onEvent?(.invalidSource(url))
    case let .commonMetadata(items):
      callbacks?.playerDidReceiveCommonMetadata(items)
    case let .chapterMetadata(groups):
      callbacks?.playerDidReceiveChapterMetadata(groups)
    case let .timedMetadata(groups):
      callbacks?.playerDidReceiveTimedMetadata(groups)
    }
  }

  /// `MPNowPlayingSession` publishes only through the linked player's current item, so a load
  /// that fails before item creation (a dead host on a cold start) leaves nothing to surface on
  /// the lock screen or CarPlay. The failed asset's item is attached purely as a publishing
  /// carrier, unobserved, so its `.failed` status cannot re-report the loader's error. A
  /// successful load replaces it; a retry's reload leaves it in place, so the surface doesn't
  /// flicker.
  private func attachPublishingCarrierItemIfNeeded() {
    guard avPlayer.currentItem == nil, let asset = mediaLoader.asset else { return }
    logger.notice("Attaching publishing-carrier item for failed load")
    let item = AVPlayerItem(asset: asset)
    nowPlayingInfoController.prepareItem(item)
    avPlayer.replaceCurrentItem(with: item)
  }
}

extension Player: AudioSessionPlayer {}

extension Player: NowPlayingSource {}

/// Resolves the URLs the player fetches.
@MainActor
protocol MediaResolving: AnyObject {
  func resolveMediaUrl(_ src: String, track: Track?) async -> MediaResolvedUrl
  func resolveNowPlayingArtwork(track: Track, imageContext: ImageContext?) async -> ImageSource?
}
