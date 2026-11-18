import Foundation
#if canImport(NitroModules)
  import NitroModules
#endif

/// Calls `onTick` every interval while `isActive` holds for the last playback state. A nil or
/// non-positive interval turns it off.
@MainActor
final class PlaybackTimer {
  // So the nonisolated deinit can invalidate it: the run loop retains a timer until then.
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
    stop() // start() keeps a running timer
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
    let timer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in
      MainActor.assumeIsolated { self?.onTick() }
    }
    self.timer = timer
    // `.common`: in the default mode a timer does not fire while a list is dragged.
    RunLoop.main.add(timer, forMode: .common)
  }

  private func stop() {
    timer?.invalidate()
    timer = nil
  }
}
