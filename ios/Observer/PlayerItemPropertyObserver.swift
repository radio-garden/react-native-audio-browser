import AVFoundation
import Foundation

/**
 Observes player item property changes and invokes callbacks passed at initialization.
 Uses modern block-based KVO for type-safe observation with automatic cleanup.
 */
final class PlayerItemPropertyObserver: NSObject {
  private var observations: [NSKeyValueObservation] = []
  private var currentMetadataOutput: AVPlayerItemMetadataOutput?

  private(set) weak var observingAVItem: AVPlayerItem?

  private let onDurationUpdate: (Double) -> Void
  private let onPlaybackLikelyToKeepUpUpdate: (Bool) -> Void
  private let onTimedMetadataReceived: ([AVTimedMetadataGroup]) -> Void

  init(
    onDurationUpdate: @escaping (Double) -> Void,
    onPlaybackLikelyToKeepUpUpdate: @escaping (Bool) -> Void,
    onTimedMetadataReceived: @escaping ([AVTimedMetadataGroup]) -> Void
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

    observingAVItem = avItem

    observations = [
      avItem.observe(\.duration, options: [.new]) { [weak self] item, _ in
        self?.onDurationUpdate(item.duration.seconds)
      },
      avItem.observe(\.loadedTimeRanges, options: [.new]) { [weak self] item, _ in
        if let duration = item.loadedTimeRanges.first?.timeRangeValue.duration {
          self?.onDurationUpdate(duration.seconds)
        }
      },
      avItem.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { [weak self] item, _ in
        self?.onPlaybackLikelyToKeepUpUpdate(item.isPlaybackLikelyToKeepUp)
      },
    ]

    // Create and add a new metadata output to the AVPlayerItem
    let metadataOutput = AVPlayerItemMetadataOutput()
    metadataOutput.setDelegate(self, queue: .main)
    avItem.add(metadataOutput)
    currentMetadataOutput = metadataOutput
  }

  func stopObservingCurrentItem() {
    observations.removeAll()

    if let observingAVItem {
      observingAVItem.removeAllMetadataOutputs()
    }

    observingAVItem = nil
    currentMetadataOutput = nil
  }
}

extension PlayerItemPropertyObserver: AVPlayerItemMetadataOutputPushDelegate {
  func metadataOutput(
    _ output: AVPlayerItemMetadataOutput,
    didOutputTimedMetadataGroups groups: [AVTimedMetadataGroup],
    from _: AVPlayerItemTrack?
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
