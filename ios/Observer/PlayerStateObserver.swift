import AVFoundation
import Foundation

/**
 Observes player state changes and invokes callbacks passed at initialization.
 Uses modern block-based KVO for type-safe observation with automatic cleanup.
 */
final class PlayerStateObserver {
  private var observations: [NSKeyValueObservation] = []

  weak var avPlayer: AVPlayer? {
    willSet {
      stopObserving()
    }
  }

  private let onStatusChange: @Sendable (AVPlayer.Status) -> Void
  private let onTimeControlStatusChange: @Sendable (AVPlayer.TimeControlStatus) -> Void

  init(
    onStatusChange: @escaping @Sendable (AVPlayer.Status) -> Void,
    onTimeControlStatusChange: @escaping @Sendable (AVPlayer.TimeControlStatus) -> Void
  ) {
    self.onStatusChange = onStatusChange
    self.onTimeControlStatusChange = onTimeControlStatusChange
  }

  deinit {
    stopObserving()
  }

  /**
   Start receiving events from this observer.
   */
  func startObserving() {
    guard let avPlayer else { return }
    stopObserving()

    observations = [
      avPlayer.observe(\.status, options: [.new, .initial]) { [weak self] player, _ in
        self?.onStatusChange(player.status)
      },
      avPlayer.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
        self?.onTimeControlStatusChange(player.timeControlStatus)
      },
    ]
  }

  func stopObserving() {
    observations.removeAll()
  }
}
