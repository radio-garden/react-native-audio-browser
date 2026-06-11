import Foundation
#if canImport(NitroModules)
  import NitroModules
#endif

/// A playback-gated repeating timer. Runs `onTick` every `interval` seconds while
/// the current playback state satisfies `isActive`. `interval == nil` (or <= 0)
/// means stopped — that is the single off-switch.
@MainActor
final class PlaybackTimer {
  // nonisolated(unsafe) for deinit cleanup — Timer must be invalidated to break
  // the RunLoop retain, and deinit is always nonisolated in Swift 6.
  private nonisolated(unsafe) var timer: Timer?
  private var interval: TimeInterval?
  private var active = false
  private let isActive: (PlaybackState) -> Bool
  private let onTick: () -> Void

  init(isActive: @escaping (PlaybackState) -> Bool, onTick: @escaping () -> Void) {
    self.isActive = isActive
    self.onTick = onTick
  }

  deinit {
    timer?.invalidate()
  }

  func setInterval(_ interval: TimeInterval?) {
    let normalized = (interval ?? 0) > 0 ? interval : nil
    if normalized == self.interval { return }
    self.interval = normalized
    stop() // restart with the new interval on the next reconcile
    reconcile()
  }

  func onPlaybackStateChanged(_ state: PlaybackState) {
    active = isActive(state)
    reconcile()
  }

  private func reconcile() {
    if let interval, active {
      start(interval)
    } else {
      stop()
    }
  }

  private func start(_ interval: TimeInterval) {
    guard timer == nil else { return }
    timer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) {
      [weak self] _ in
      MainActor.assumeIsolated { self?.onTick() }
    }
  }

  private func stop() {
    timer?.invalidate()
    timer = nil
  }
}
