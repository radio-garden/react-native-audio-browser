@preconcurrency import AVFoundation

// Delivered on the main queue through AVPlayerItemMetadataOutputPushDelegate.
extension AVTimedMetadataGroup: @retroactive @unchecked Sendable {}

/// Observes one AVPlayerItem from creation to `invalidate()`: its status and buffer, the
/// end-of-item and stall notifications, and its timed metadata. KVO and notifications arrive off
/// the main thread; anything that lands after `invalidate()` is dropped, so an old item's late
/// failure never reaches the next item's handlers.
@MainActor
final class ItemObserver: NSObject {
  enum Event {
    case likelyToKeepUp(Bool)
    case failed(Error?)
    case playedToEnd
    case failedToPlayToEnd(Error?)
    /// The buffer emptied mid-playback.
    case stalled
    case timedMetadata([AVTimedMetadataGroup])
  }

  private let item: AVPlayerItem
  private let onEvent: @MainActor (Event) -> Void
  private var observations: [NSKeyValueObservation] = []
  private var notificationTokens: [any NSObjectProtocol] = []
  private let metadataOutput = AVPlayerItemMetadataOutput()
  private(set) var isActive = true

  init(item: AVPlayerItem, onEvent: @escaping @MainActor (Event) -> Void) {
    self.item = item
    self.onEvent = onEvent
    super.init()
    observations = [
      item.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { [weak self] item, _ in
        let isLikely = item.isPlaybackLikelyToKeepUp
        Task { @MainActor in self?.emit(.likelyToKeepUp(isLikely)) }
      },
      item.observe(\.status, options: [.new]) { [weak self] item, _ in
        guard item.status == .failed else { return }
        let error = item.error
        Task { @MainActor in self?.emit(.failed(error)) }
      },
    ]
    notificationTokens = [
      observe(.AVPlayerItemDidPlayToEndTime) { _ in .playedToEnd },
      observe(.AVPlayerItemFailedToPlayToEndTime) {
        .failedToPlayToEnd($0.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error)
      },
      observe(.AVPlayerItemPlaybackStalled) { _ in .stalled },
    ]
    metadataOutput.setDelegate(self, queue: .main)
    item.add(metadataOutput)
  }

  func invalidate() {
    guard isActive else { return }
    isActive = false
    observations.removeAll()
    for token in notificationTokens {
      NotificationCenter.default.removeObserver(token)
    }
    notificationTokens = []
    item.remove(metadataOutput)
  }

  private func observe(
    _ name: Notification.Name,
    _ event: @escaping @Sendable (Notification) -> Event,
  ) -> any NSObjectProtocol {
    NotificationCenter.default.addObserver(forName: name, object: item, queue: nil) { [weak self] notification in
      let event = event(notification)
      Task { @MainActor in self?.emit(event) }
    }
  }

  private func emit(_ event: Event) {
    guard isActive else { return }
    onEvent(event)
  }
}

extension ItemObserver: AVPlayerItemMetadataOutputPushDelegate {
  nonisolated func metadataOutput(
    _: AVPlayerItemMetadataOutput,
    didOutputTimedMetadataGroups groups: [AVTimedMetadataGroup],
    from _: AVPlayerItemTrack?,
  ) {
    MainActor.assumeIsolated { emit(.timedMetadata(groups)) }
  }
}
