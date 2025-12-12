import AVFoundation
import Foundation

/**
 Observes player item property changes and invokes callbacks passed at initialization.
 */
final class PlayerItemPropertyObserver: NSObject, @unchecked Sendable {
  nonisolated(unsafe) private static var context = 0
  private var currentMetadataOutput: AVPlayerItemMetadataOutput?

  private enum AVPlayerItemKeyPath {
    static let duration = #keyPath(AVPlayerItem.duration)
    static let loadedTimeRanges = #keyPath(AVPlayerItem.loadedTimeRanges)
    static let playbackLikelyToKeepUp = #keyPath(AVPlayerItem.isPlaybackLikelyToKeepUp)
  }

  private(set) var isObserving: Bool = false
  private(set) weak var observingAVItem: AVPlayerItem?

  private let onDurationUpdate: (Double) -> Void
  private let onPlaybackLikelyToKeepUpUpdate: (Bool) -> Void
  private let onTimedMetadataReceived: ([AVTimedMetadataGroup]) -> Void

  init(
    onDurationUpdate: @escaping (Double) -> Void,
    onPlaybackLikelyToKeepUpUpdate: @escaping (Bool) -> Void,
    onTimedMetadataReceived: @escaping ([AVTimedMetadataGroup]) -> Void,
  ) {
    self.onDurationUpdate = onDurationUpdate
    self.onPlaybackLikelyToKeepUpUpdate = onPlaybackLikelyToKeepUpUpdate
    self.onTimedMetadataReceived = onTimedMetadataReceived
  }

  deinit {
    stopObservingCurrentItem()
  }

  /**
   Start observing an AVPlayerItem. Will remove self as observer from old item, if any.

   - parameter avItem: The AVPlayerItem to observe.
   */
  func startObserving(item avItem: AVPlayerItem) {
    stopObservingCurrentItem()

    isObserving = true
    observingAVItem = avItem
    avItem.addObserver(
      self,
      forKeyPath: AVPlayerItemKeyPath.duration,
      options: [.new],
      context: &PlayerItemPropertyObserver.context,
    )
    avItem.addObserver(
      self,
      forKeyPath: AVPlayerItemKeyPath.loadedTimeRanges,
      options: [.new],
      context: &PlayerItemPropertyObserver.context,
    )
    avItem.addObserver(
      self,
      forKeyPath: AVPlayerItemKeyPath.playbackLikelyToKeepUp,
      options: [.new],
      context: &PlayerItemPropertyObserver.context,
    )

    // Create and add a new metadata output to the AVPlayerItem.
    let metadataOutput = AVPlayerItemMetadataOutput()
    metadataOutput.setDelegate(self, queue: .main)
    avItem.add(metadataOutput)
    currentMetadataOutput = metadataOutput
  }

  func stopObservingCurrentItem() {
    guard let observingAVItem, isObserving else {
      return
    }

    observingAVItem.removeObserver(
      self,
      forKeyPath: AVPlayerItemKeyPath.duration,
      context: &PlayerItemPropertyObserver.context,
    )
    observingAVItem.removeObserver(
      self,
      forKeyPath: AVPlayerItemKeyPath.loadedTimeRanges,
      context: &PlayerItemPropertyObserver.context,
    )
    observingAVItem.removeObserver(
      self,
      forKeyPath: AVPlayerItemKeyPath.playbackLikelyToKeepUp,
      context: &PlayerItemPropertyObserver.context,
    )

    // Remove all metadata outputs from the AVPlayerItem.
    observingAVItem.removeAllMetadataOutputs()

    isObserving = false
    self.observingAVItem = nil
    currentMetadataOutput = nil
  }

  override func observeValue(
    forKeyPath keyPath: String?,
    of object: Any?,
    change: [NSKeyValueChangeKey: Any]?,
    context: UnsafeMutableRawPointer?,
  ) {
    guard context == &PlayerItemPropertyObserver.context, let observedKeyPath = keyPath else {
      super.observeValue(forKeyPath: keyPath, of: object, change: change, context: context)
      return
    }

    switch observedKeyPath {
    case AVPlayerItemKeyPath.duration:
      if let duration = change?[.newKey] as? CMTime {
        onDurationUpdate(duration.seconds)
      }

    case AVPlayerItemKeyPath.loadedTimeRanges:
      if let ranges = change?[.newKey] as? [NSValue],
         let duration = ranges.first?.timeRangeValue.duration
      {
        onDurationUpdate(duration.seconds)
      }

    case AVPlayerItemKeyPath.playbackLikelyToKeepUp:
      if let playbackLikelyToKeepUp = change?[.newKey] as? Bool {
        onPlaybackLikelyToKeepUpUpdate(playbackLikelyToKeepUp)
      }

    default: break
    }
  }
}

extension PlayerItemPropertyObserver: AVPlayerItemMetadataOutputPushDelegate {
  func metadataOutput(
    _ output: AVPlayerItemMetadataOutput,
    didOutputTimedMetadataGroups groups: [AVTimedMetadataGroup],
    from _: AVPlayerItemTrack?,
  ) {
    if output == currentMetadataOutput {
      onTimedMetadataReceived(groups)
    }
  }
}

extension AVPlayerItem {
  func removeAllMetadataOutputs() {
    for output in outputs.filter({ $0 is AVPlayerItemMetadataOutput }) {
      remove(output)
    }
  }
}
