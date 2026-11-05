import Foundation

/// Ramps the volume down to silence over a duration (the sleep-timer fade).
/// The gain follows a squared curve: loudness perception is logarithmic, so a
/// linear ramp sounds like a late cutoff rather than a fade.
@MainActor
final class VolumeFader {
  private static let stepInterval: TimeInterval = 0.05

  private let getVolume: () -> Float
  private let setVolume: (Float) -> Void
  // nonisolated(unsafe) so `deinit`, which is nonisolated in Swift 6, can invalidate it.
  private nonisolated(unsafe) var timer: Timer?

  /// The volume before the fade, to restore once it resolves. Stays set from
  /// `start` until `cancel` or `resolve`, also after the ramp reaches silence.
  private(set) var originalVolume: Float?

  var isActive: Bool { originalVolume != nil }

  private(set) var duration: TimeInterval?

  init(getVolume: @escaping () -> Float, setVolume: @escaping (Float) -> Void) {
    self.getVolume = getVolume
    self.setVolume = setVolume
  }

  /// Starts a fade, restarting from the pre-fade volume if one is running.
  /// A `duration` of zero or less marks the fade active without touching the
  /// volume.
  func start(duration: TimeInterval) {
    // A running fade's current volume is part-faded; its captured one is the
    // value to restore.
    let baseVolume = originalVolume ?? getVolume()
    cancel(restoringVolume: false)
    originalVolume = baseVolume
    self.duration = duration
    guard duration > 0 else { return }

    let startDate = Date()
    let timer = Timer(timeInterval: Self.stepInterval, repeats: true) { [weak self] _ in
      MainActor.assumeIsolated {
        guard let self else { return }
        let progress = min(1, Date().timeIntervalSince(startDate) / duration)
        let remaining = Float(1 - progress)
        self.setVolume(baseVolume * remaining * remaining)
        if progress >= 1 {
          self.timer?.invalidate()
          self.timer = nil
        }
      }
    }
    self.timer = timer
    // `.common` so the ramp keeps running while UIKit tracks a scroll.
    RunLoop.main.add(timer, forMode: .common)
  }

  /// Stops the ramp and ends the fade. With `restoringVolume` the pre-fade
  /// volume comes back at once; without it the volume stays where the ramp
  /// left it, for a caller that halts playback before restoring.
  func cancel(restoringVolume: Bool) {
    timer?.invalidate()
    timer = nil
    guard let originalVolume else { return }
    self.originalVolume = nil
    duration = nil
    if restoringVolume { setVolume(originalVolume) }
  }

  /// Stops the ramp, runs `halt`, then restores the pre-fade volume (if a fade
  /// was running). Restoring before halting would let full-volume audio out.
  func resolve(around halt: () -> Void) {
    let preFadeVolume = originalVolume
    cancel(restoringVolume: false)
    halt()
    if let preFadeVolume { setVolume(preFadeVolume) }
  }

  deinit {
    timer?.invalidate()
  }
}
