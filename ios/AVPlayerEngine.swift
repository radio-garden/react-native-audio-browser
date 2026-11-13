@preconcurrency import AVFoundation
import Foundation
import MediaPlayer
import NitroModules
import os.log

/// Carries out what the `Player` decides; holds no playback policy.
@MainActor
final class AVPlayerEngine: PlayerEngine {
  let logger = Logger(subsystem: "com.audiobrowser", category: "AVPlayerEngine")

  var onEvent: (@MainActor (EngineEvent) -> Void)?
  private weak var callbacks: AVPlayerEngineCallbacks?
  private weak var audioSession: AudioSessionController?

  let nowPlayingInfoController: NowPlayingInfoController
  let nowPlayingUpdater: NowPlayingUpdater
  let remoteCommandController: RemoteCommandController
  private let mediaLoader: MediaLoader
  private let silentAudioPrimer = SilentAudioPrimer()

  private var avPlayer = AVPlayer()
  private var playerObserver: PlayerObserver?
  private var itemObserver: ItemObserver?
  private var rate: Float = 1
  private var skipAvailability = (canNext: false, canPrevious: false)
  private var icyCharset: String?
  private let bufferDuration: Double

  /// - Parameters:
  ///   - bufferDuration: 0 lets AVPlayer choose, minimising stalls.
  ///   - nowPlayingEnabled: When false the now-playing surface ignores the override and formatter.
  init(
    callbacks: AVPlayerEngineCallbacks?,
    resolver: MediaResolving?,
    audioSession: AudioSessionController,
    networkStatus: (any NetworkStatusProviding)?,
    bufferDuration: Double,
    nowPlayingEnabled: Bool,
    nowPlayingFormatter: ((FormatNowPlayingParams) -> Promise<NowPlayingUpdate?>)?,
    nowPlayingInfoController: NowPlayingInfoController = NowPlayingInfoController(),
  ) {
    self.callbacks = callbacks
    self.audioSession = audioSession
    self.bufferDuration = bufferDuration
    self.nowPlayingInfoController = nowPlayingInfoController
    nowPlayingUpdater = NowPlayingUpdater(
      nowPlayingInfoController: nowPlayingInfoController,
      networkStatus: networkStatus,
      enabled: nowPlayingEnabled,
      formatter: nowPlayingFormatter,
    )
    remoteCommandController = RemoteCommandController(callbacks: callbacks)
    mediaLoader = MediaLoader { [weak resolver] src, track in
      await resolver?.resolveMediaUrl(src, track: track) ?? MediaResolvedUrl(url: src, headers: nil, userAgent: nil)
    }

    mediaLoader.bufferDuration = bufferDuration
    mediaLoader.onEvent = { [weak self] in self?.mediaLoaderDidEmit($0) }
    nowPlayingUpdater.artworkUrlResolver = { [weak resolver] track, imageContext in
      await resolver?.resolveNowPlayingArtwork(track: track, imageContext: imageContext)
    }
    nowPlayingUpdater.onChanged = { [weak self] in self?.callbacks?.playerDidChangeNowPlaying($0) }
    remoteCommandController.skipAvailability = { [weak self] in
      self?.skipAvailability ?? (false, false)
    }
    setUpAVPlayer()
  }

  /// Releases the player for good, with its now-playing session, observers and remote commands.
  func destroy() {
    remoteCommandController.disableAll()
    nowPlayingInfoController.unlinkPlayer()
    playerObserver?.invalidate()
    playerObserver = nil
    stopObservingItem()
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

  /// The item's duration; for a live stream, the length of its seekable window (an HLS playlist or
  /// DVR window), or 0 when it has none (e.g. Icecast) or there is no item.
  // Not `item.asset.duration`: deprecated since iOS 16, and it blocks the main actor fetching a
  // remote asset's duration.
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
    let seconds = item.currentTime().seconds
    return seconds.isFinite ? seconds : nil
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

  func resolveAndLoad(src: String, track: Track, shortStartBuffer: Bool) {
    icyCharset = track.icyCharset
    mediaLoader.resolveAndLoad(src: src, track: track, shortStartBuffer: shortStartBuffer)
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

  func replaceItemWithNowPlayingCarrier() {
    dropAsset()
    let carrier = AVPlayerItem(asset: AVMutableComposition())
    nowPlayingInfoController.prepareItem(carrier)
    avPlayer.replaceCurrentItem(with: carrier)
  }

  func stopObservingItem() {
    itemObserver?.invalidate()
    itemObserver = nil
  }

  var playerFailed: Bool { avPlayer.status == .failed }

  func recreate() {
    playerObserver?.invalidate()
    nowPlayingInfoController.unlinkPlayer()
    let volume = avPlayer.volume
    avPlayer = AVPlayer()
    avPlayer.volume = volume
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
    avPlayer.currentItem?.audioTimePitchAlgorithm = .timeDomain
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

  func renderNowPlaying() {
    nowPlayingUpdater.render()
  }

  func nowPlayingTrackChanged() {
    nowPlayingUpdater.trackChanged()
  }

  func applyTimedMetadata(_ metadata: TimedMetadata) {
    nowPlayingUpdater.receivedTimedMetadata(metadata)
  }

  func clearTimedMetadata() {
    nowPlayingUpdater.clearTimedMetadata()
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

  // MARK: - Audio Session

  func activateAudioSession() {
    audioSession?.activate { [weak self] in self?.nowPlayingInfoController.reactivateSessionIfNeeded() }
  }

  func cancelAudioSessionRelease() {
    audioSession?.cancelRelease()
  }

  func scheduleAudioSessionRelease() {
    audioSession?.scheduleRelease()
  }

  // MARK: - Observed Events

  private func playerDidEmit(_ event: PlayerObserver.Event) {
    switch event {
    case let .status(status):
      if status == .failed { reportFailure(avPlayer.error) }
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
    case .readyToPlay:
      // Ends the short start buffer `MediaLoader.resolveAndLoad` may have given the item.
      avPlayer.currentItem?.preferredForwardBufferDuration = bufferDuration / 1000
    case let .likelyToKeepUp(isLikely): onEvent?(.likelyToKeepUp(isLikely))
    case let .failed(error), let .failedToPlayToEnd(error): reportFailure(error)
    case .playedToEnd: onEvent?(.playedToEnd)
    case .stalled: onEvent?(.stalled)
    case let .timedMetadata(groups): reportTimedMetadata(groups)
    }
  }

  private func reportFailure(_ error: Error?) {
    onEvent?(.failed(error ?? avPlayer.currentItem?.error, httpStatusCode: currentItemHttpStatusCode))
  }

  // `AVPlayerItem.error` never carries the HTTP status (a 404 arrives as an opaque CoreMedia
  // error); the item's error log is the only place it surfaces.
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
      reportTimedMetadata(groups)
    }
  }

  private func reportTimedMetadata(_ groups: [AVTimedMetadataGroup]) {
    for group in groups {
      if let metadata = TimedMetadata.from(items: group.items, icyCharset: icyCharset) {
        onEvent?(.timedMetadata(metadata))
      }
    }
  }

  // `MPNowPlayingSession` publishes only through the linked player's current item, so a load that
  // fails before an item exists (a dead host on a cold start) shows nothing on the lock screen or
  // CarPlay. The failed asset's item carries the publishing, unobserved so its `.failed` status
  // cannot re-report the loader's error. A successful load replaces it; a retry leaves it in
  // place, so the surface doesn't flicker.
  private func attachPublishingCarrierItemIfNeeded() {
    guard avPlayer.currentItem == nil, let asset = mediaLoader.asset else { return }
    logger.notice("Attaching publishing-carrier item for failed load")
    let item = AVPlayerItem(asset: asset)
    nowPlayingInfoController.prepareItem(item)
    avPlayer.replaceCurrentItem(with: item)
  }
}

@MainActor
protocol MediaResolving: AnyObject {
  func resolveMediaUrl(_ src: String, track: Track?) async -> MediaResolvedUrl
  func resolveNowPlayingArtwork(track: Track, imageContext: ImageContext?) async -> ImageSource?
}
