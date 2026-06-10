import Foundation
#if canImport(NitroModules)
import NitroModules
#endif

/// Emits a fixed-cadence tick while playback is active AND enabled.
/// Carries no payload; consumers measure playback time themselves.
@MainActor
class PlaybackIntervalManager {
  private static let tickInterval: TimeInterval = 1.0

  nonisolated(unsafe) private var timer: Timer?
  private var enabled = false
  private var active = false
  private let onTick: () -> Void

  init(onTick: @escaping () -> Void) {
    self.onTick = onTick
  }

  deinit {
    timer?.invalidate()
  }

  func setEnabled(_ enabled: Bool) {
    if self.enabled == enabled { return }
    self.enabled = enabled
    reconcile()
  }

  func onPlaybackStateChanged(_ state: PlaybackState) {
    switch state {
    // Only actual playback counts as "playback time"; buffering/loading freeze
    // the clock so consumers measure cumulative *playing* time.
    case .playing:
      active = true
    case .loading, .buffering, .paused, .stopped, .ended, .error:
      active = false
    default:
      break
    }
    reconcile()
  }

  private func reconcile() {
    if enabled && active {
      start()
    } else {
      stop()
    }
  }

  private func start() {
    guard timer == nil else { return }
    timer = Timer.scheduledTimer(
      withTimeInterval: Self.tickInterval,
      repeats: true
    ) { [weak self] _ in
      MainActor.assumeIsolated {
        self?.onTick()
      }
    }
  }

  private func stop() {
    timer?.invalidate()
    timer = nil
  }
}
