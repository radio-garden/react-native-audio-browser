import AVFoundation

/// Observes one AVPlayer from creation to `invalidate()`: its status, its time-control status,
/// and the moment audio starts. A recreated player gets a new observer, so nothing a released
/// player reports reaches its successor.
@MainActor
final class PlayerObserver {
  enum Event {
    case status(AVPlayer.Status)
    case timeControlStatus(AVPlayer.TimeControlStatus)
    /// Playback crossed the first millisecond.
    case audioDidStart
  }

  /// A boundary time cannot be zero.
  private static let startBoundaryTime = CMTime(value: 1, timescale: 1000)

  private let player: AVPlayer
  private let onEvent: @MainActor (Event) -> Void
  private var observations: [NSKeyValueObservation] = []
  private(set) var boundaryObserverToken: Any?
  private var isActive = true

  init(player: AVPlayer, onEvent: @escaping @MainActor (Event) -> Void) {
    self.player = player
    self.onEvent = onEvent
    observations = [
      player.observe(\.status, options: [.new, .initial]) { [weak self] player, _ in
        let status = player.status
        Task { @MainActor in self?.emit(.status(status)) }
      },
      player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
        let status = player.timeControlStatus
        Task { @MainActor in self?.emit(.timeControlStatus(status)) }
      },
    ]
    boundaryObserverToken = player.addBoundaryTimeObserver(
      forTimes: [NSValue(time: Self.startBoundaryTime)], queue: nil,
    ) { [weak self] in
      Task { @MainActor in self?.emit(.audioDidStart) }
    }
  }

  /// Stops all delivery, including events already hopping to the main actor. An AVPlayer
  /// retains its time observers, so one left registered outlives the observer (#96).
  func invalidate() {
    isActive = false
    observations.removeAll()
    if let boundaryObserverToken {
      player.removeTimeObserver(boundaryObserverToken)
      self.boundaryObserverToken = nil
    }
  }

  private func emit(_ event: Event) {
    guard isActive else { return }
    onEvent(event)
  }
}
