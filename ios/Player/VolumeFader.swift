import Foundation

/// Ramps the volume down to silence by a deadline on the scheduler's clock. The gain falls
/// linearly in decibels, so every second of the fade is heard; a gain that falls linearly spends
/// its last seconds inaudible.
@MainActor
final class VolumeFader {
  static let stepInterval: TimeInterval = 0.05
  static let floorDecibels: Float = -50

  private let getVolume: () -> Float
  private let setVolume: (Float) -> Void
  private let scheduler: any SleepTimerScheduling
  // So the nonisolated deinit can cancel it.
  private nonisolated(unsafe) var step: (any SleepTimerJob)?

  /// The volume to restore, set from `start` until `cancel` or `resolve`, also once the ramp has
  /// reached silence.
  private(set) var originalVolume: Float?

  var isActive: Bool { originalVolume != nil }

  private(set) var duration: TimeInterval?

  init(
    getVolume: @escaping () -> Float,
    setVolume: @escaping (Float) -> Void,
    scheduler: any SleepTimerScheduling,
  ) {
    self.getVolume = getVolume
    self.setVolume = setVolume
    self.scheduler = scheduler
  }

  static func gain(at progress: Double) -> Float {
    if progress >= 1 { return 0 }
    return pow(10, floorDecibels * Float(max(0, progress)) / 20)
  }

  /// Starts a fade reaching silence at `end`, `duration` seconds long; started late, it joins the
  /// ramp where it would be by now. A running fade restarts from its pre-fade volume. A `duration`
  /// of zero or less marks the fade active without touching the volume.
  func start(endingAt end: TimeInterval, duration: TimeInterval) {
    let baseVolume = originalVolume ?? getVolume()
    cancel(restoringVolume: false)
    originalVolume = baseVolume
    self.duration = duration
    guard duration > 0 else { return }
    ramp(base: baseVolume, end: end, duration: duration)
  }

  private func ramp(base: Float, end: TimeInterval, duration: TimeInterval) {
    let progress = 1 - (end - scheduler.now) / duration
    setVolume(base * Self.gain(at: progress))
    step = progress < 1
      ? scheduler.schedule(after: Self.stepInterval) { [weak self] in
        self?.ramp(base: base, end: end, duration: duration)
      }
      : nil
  }

  /// Without `restoringVolume` the volume stays where the ramp left it.
  func cancel(restoringVolume: Bool) {
    step?.cancel()
    step = nil
    guard let originalVolume else { return }
    self.originalVolume = nil
    duration = nil
    if restoringVolume { setVolume(originalVolume) }
  }

  /// Stops the fade, runs `halt`, then restores the pre-fade volume: restoring first would let
  /// full-volume audio out.
  func resolve(around halt: () -> Void) {
    let preFadeVolume = originalVolume
    cancel(restoringVolume: false)
    halt()
    if let preFadeVolume { setVolume(preFadeVolume) }
  }

  deinit {
    step?.cancel()
  }
}
