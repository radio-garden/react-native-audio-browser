import Foundation
#if canImport(NitroModules)
  import NitroModules
#endif

/// A repeating timer gated on playback state: runs `onTick` every `interval` seconds while
/// `isActive` holds for the current state. A nil or non-positive interval turns it off.
@MainActor
final class PlaybackTimer {
  // nonisolated(unsafe) so the nonisolated deinit (Swift 6) can invalidate it; the run loop
  // retains a timer until it is invalidated.
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
    stop() // start() keeps a running timer, so drop it for the new interval
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
    // `.common`, not the `.default` mode `Timer.scheduledTimer` uses: while a list is dragged the
    // main run loop is in tracking mode, where a default-mode timer does not fire and its missed
    // repeats are dropped, freezing progress events and losing interval ticks.
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
