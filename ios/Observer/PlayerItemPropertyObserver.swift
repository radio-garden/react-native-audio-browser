@preconcurrency import AVFoundation
import Foundation

// AVTimedMetadataGroup is not Sendable, but we only use it on the main thread
// via AVPlayerItemMetadataOutputPushDelegate (queue: .main)
extension AVTimedMetadataGroup: @retroactive @unchecked Sendable {}

/**
 Observes player item property changes and invokes callbacks passed at initialization.
 Uses modern block-based KVO for type-safe observation with automatic cleanup.
 */
@MainActor final class PlayerItemPropertyObserver: NSObject {
  private var observations: [NSKeyValueObservation] = []
  private var currentMetadataOutput: AVPlayerItemMetadataOutput?

  private(set) weak var observingAVItem: AVPlayerItem?

  private let onDurationUpdate: @MainActor (Double) -> Void
  private let onPlaybackLikelyToKeepUpUpdate: @MainActor (Bool) -> Void
  private let onStatusChange: @MainActor (AVPlayerItem.Status, Error?) -> Void
  // Note: AVTimedMetadataGroup is not Sendable, but this callback is always invoked
  // on the main thread via AVPlayerItemMetadataOutputPushDelegate (queue: .main)
  private let onTimedMetadataReceived: @MainActor ([AVTimedMetadataGroup]) -> Void

  init(
    onDurationUpdate: @escaping @MainActor (Double) -> Void,
    onPlaybackLikelyToKeepUpUpdate: @escaping @MainActor (Bool) -> Void,
    onStatusChange: @escaping @MainActor (AVPlayerItem.Status, Error?) -> Void,
    onTimedMetadataReceived: @escaping @MainActor ([AVTimedMetadataGroup]) -> Void,
  ) {
    self.onDurationUpdate = onDurationUpdate
    self.onPlaybackLikelyToKeepUpUpdate = onPlaybackLikelyToKeepUpUpdate
    self.onStatusChange = onStatusChange
    self.onTimedMetadataReceived = onTimedMetadataReceived
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
        let seconds = item.duration.seconds
        let identity = ObjectIdentifier(item)
        Task { @MainActor in
          guard let self, self.isObserving(identity) else { return }
          self.onDurationUpdate(seconds)
        }
      },
      avItem.observe(\.loadedTimeRanges, options: [.new]) { [weak self] item, _ in
        if let duration = item.loadedTimeRanges.first?.timeRangeValue.duration {
          let seconds = duration.seconds
          let identity = ObjectIdentifier(item)
          Task { @MainActor in
            guard let self, self.isObserving(identity) else { return }
            self.onDurationUpdate(seconds)
          }
        }
      },
      avItem.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { [weak self] item, _ in
        let isLikely = item.isPlaybackLikelyToKeepUp
        let identity = ObjectIdentifier(item)
        Task { @MainActor in
          guard let self, self.isObserving(identity) else { return }
          self.onPlaybackLikelyToKeepUpUpdate(isLikely)
        }
      },
      avItem.observe(\.status, options: [.new]) { [weak self] item, _ in
        let status = item.status
        let error = item.error
        let identity = ObjectIdentifier(item)
        Task { @MainActor in
          guard let self, self.isObserving(identity) else { return }
          self.onStatusChange(status, error)
        }
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

  /// True when `identity` still names the observed item. KVO fires off the main thread, so
  /// the main-actor hop can land after the observer switched items — without this check a
  /// stale delivery (e.g. the old item's `.failed`) is attributed to the freshly loaded one.
  private func isObserving(_ identity: ObjectIdentifier) -> Bool {
    observingAVItem.map(ObjectIdentifier.init) == identity
  }
}

extension PlayerItemPropertyObserver: AVPlayerItemMetadataOutputPushDelegate {
  nonisolated func metadataOutput(
    _ output: AVPlayerItemMetadataOutput,
    didOutputTimedMetadataGroups groups: [AVTimedMetadataGroup],
    from _: AVPlayerItemTrack?,
  ) {
    // Delegate is called on main thread (queue: .main), so we can assume isolation
    MainActor.assumeIsolated {
      if output == currentMetadataOutput {
        onTimedMetadataReceived(groups)
      }
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
