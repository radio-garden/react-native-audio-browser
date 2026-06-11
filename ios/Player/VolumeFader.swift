import Foundation

/// Ramps the player volume down over a duration (sleep-timer fade).
/// The gain follows a squared curve: loudness perception is logarithmic, so a
/// linear gain ramp sounds like a late cutoff rather than a fade.
@MainActor
final class VolumeFader {
  private static let stepInterval: TimeInterval = 0.05

  private let getVolume: () -> Float
  private let setVolume: (Float) -> Void
  // nonisolated(unsafe) for deinit cleanup — deinit is always nonisolated in Swift 6.
  private nonisolated(unsafe) var timer: Timer?

  /// Volume captured when the fade started; restored after the fade resolves.
  /// Non-nil from fade start until cancel/completion, even once the ramp has
  /// reached silence.
  private(set) var originalVolume: Float?

  var isActive: Bool { originalVolume != nil }

  init(getVolume: @escaping () -> Float, setVolume: @escaping (Float) -> Void) {
    self.getVolume = getVolume
    self.setVolume = setVolume
  }

  func start(duration: TimeInterval) {
    // If a fade is already running, keep its captured volume as the baseline —
    // the current (partially faded) volume is not the value to restore.
    let baseVolume = originalVolume ?? getVolume()
    cancel(restoringVolume: false)
    originalVolume = baseVolume
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
    RunLoop.main.add(timer, forMode: .common)
  }

  /// Stops the ramp. With `restoringVolume` the pre-fade volume is put back
  /// immediately (cancellation); without it the caller restores after pausing
  /// (completion path — restoring first would let full-volume audio slip out).
  func cancel(restoringVolume: Bool) {
    timer?.invalidate()
    timer = nil
    guard let originalVolume else { return }
    self.originalVolume = nil
    if restoringVolume { setVolume(originalVolume) }
  }

  /// Resolves the fade around a halting action: stops the ramp, runs `halt`,
  /// then restores the pre-fade volume — in that order, because restoring
  /// before halting would let full-volume audio slip out. The restore is
  /// skipped when no fade was running.
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
