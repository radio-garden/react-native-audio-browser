import Foundation

/// Ramps the volume down to silence by a deadline (the sleep-timer fade), stepping on the
/// scheduler's clock. The gain falls linearly in decibels to `floorDecibels`, so every second of
/// the fade is heard as one; a gain that falls linearly or squared spends its last seconds
/// inaudible.
@MainActor
final class VolumeFader {
  static let stepInterval: TimeInterval = 0.05
  static let floorDecibels: Float = -50

  private let getVolume: () -> Float
  private let setVolume: (Float) -> Void
  private let scheduler: any SleepTimerScheduling
  // nonisolated(unsafe) so `deinit`, which is nonisolated in Swift 6, can cancel it.
  private nonisolated(unsafe) var step: (any SleepTimerJob)?

  /// The volume before the fade, to restore once it resolves. Stays set from
  /// `start` until `cancel` or `resolve`, also after the ramp reaches silence.
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

  /// The gain, relative to the pre-fade volume, `progress` (0...1) of the way through a fade.
  static func gain(at progress: Double) -> Float {
    if progress >= 1 { return 0 }
    return pow(10, floorDecibels * Float(max(0, progress)) / 20)
  }

  /// The fade as linear segments in gain between `segments + 1` points of `gain(at:)`, on a
  /// timeline where it reaches silence at `end`. Clipped at 0: a timeline has nothing before it.
  static func ramps(
    endingAt end: TimeInterval,
    duration: TimeInterval,
    segments: Int,
  ) -> [(fromGain: Float, toGain: Float, start: TimeInterval, end: TimeInterval)] {
    guard duration > 0, segments > 0 else { return [] }
    let time = { (progress: Double) in end - duration * (1 - progress) }
    return (0 ..< segments).compactMap { index in
      let from = Double(index) / Double(segments)
      let to = Double(index + 1) / Double(segments)
      guard time(to) > 0 else { return nil }
      if time(from) < 0 { return (gain(at: 1 - end / duration), gain(at: to), 0, time(to)) }
      return (gain(at: from), gain(at: to), time(from), time(to))
    }
  }

  /// Starts a fade that reaches silence at `end` on the scheduler's clock, `duration` seconds
  /// after it would have begun. Started late, it joins the ramp where it would be by now.
  /// Restarts from the pre-fade volume if one is running. A `duration` of zero or less marks the
  /// fade active without touching the volume.
  func start(endingAt end: TimeInterval, duration: TimeInterval) {
    // A running fade's current volume is part-faded; its captured one is the
    // value to restore.
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

  /// Stops the ramp and ends the fade. With `restoringVolume` the pre-fade
  /// volume comes back at once; without it the volume stays where the ramp
  /// left it, for a caller that halts playback before restoring.
  func cancel(restoringVolume: Bool) {
    step?.cancel()
    step = nil
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
    step?.cancel()
  }
}
