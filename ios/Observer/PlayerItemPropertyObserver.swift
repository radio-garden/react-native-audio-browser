@preconcurrency import AVFoundation
import Foundation

// AVTimedMetadataGroup is not Sendable, but we only use it on the main thread
// via AVPlayerItemMetadataOutputPushDelegate (queue: .main)
extension AVTimedMetadataGroup: @retroactive @unchecked Sendable {}

/**
 Observes player item property changes and invokes callbacks passed at initialization.
 Uses modern block-based KVO for type-safe observation with automatic cleanup.
 */
final class PlayerItemPropertyObserver: NSObject, @unchecked Sendable {
  private var observations: [NSKeyValueObservation] = []
  private var currentMetadataOutput: AVPlayerItemMetadataOutput?

  private(set) weak var observingAVItem: AVPlayerItem?

  private let onDurationUpdate: @Sendable (Double) -> Void
  private let onPlaybackLikelyToKeepUpUpdate: @Sendable (Bool) -> Void
  private let onStatusChange: @Sendable (AVPlayerItem.Status, Error?) -> Void
  // Note: AVTimedMetadataGroup is not Sendable, but this callback is always invoked
  // on the main thread via AVPlayerItemMetadataOutputPushDelegate (queue: .main)
  private let onTimedMetadataReceived: @MainActor ([AVTimedMetadataGroup]) -> Void

  init(
    onDurationUpdate: @escaping @Sendable (Double) -> Void,
    onPlaybackLikelyToKeepUpUpdate: @escaping @Sendable (Bool) -> Void,
    onStatusChange: @escaping @Sendable (AVPlayerItem.Status, Error?) -> Void,
    onTimedMetadataReceived: @escaping @MainActor ([AVTimedMetadataGroup]) -> Void,
  ) {
    self.onDurationUpdate = onDurationUpdate
    self.onPlaybackLikelyToKeepUpUpdate = onPlaybackLikelyToKeepUpUpdate
    self.onStatusChange = onStatusChange
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
      avItem.observe(\.status, options: [.new]) { [weak self] item, _ in
        self?.onStatusChange(item.status, item.error)
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
      // removeAllMetadataOutputs is MainActor-isolated; this observer is used from main thread
      MainActor.assumeIsolated {
        observingAVItem.removeAllMetadataOutputs()
      }
    }

    observingAVItem = nil
    currentMetadataOutput = nil
  }
}

extension PlayerItemPropertyObserver: AVPlayerItemMetadataOutputPushDelegate {
  func metadataOutput(
    _ output: AVPlayerItemMetadataOutput,
    didOutputTimedMetadataGroups groups: [AVTimedMetadataGroup],
    from _: AVPlayerItemTrack?,
  ) {
    if output == currentMetadataOutput {
      // Delegate is called on main thread (queue: .main), so we can assume isolation
      MainActor.assumeIsolated { onTimedMetadataReceived(groups) }
    }
  }
}

extension AVPlayerItem {
  @MainActor
  func removeAllMetadataOutputs() {
    for output in outputs.filter({ $0 is AVPlayerItemMetadataOutput }) {
      remove(output)
    }
  }
}
