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
    // `.common`, not the `.default` mode `Timer.scheduledTimer` installs: UIKit
    // runs the main run loop in tracking mode while a list is being dragged, and
    // a default-mode timer does not fire there. Missed repeats are dropped rather
    // than replayed, so progress events would freeze and interval ticks would be
    // lost for as long as the user scrolls. (VolumeFader already does this.)
    let timer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in
      MainActor.assumeIsolated { self?.onTick() }
    }
    self.timer = timer
    RunLoop.main.add(timer, forMode: .common)
  }

  private func stop() {
    timer?.invalidate()
    timer = nil
  }
}
