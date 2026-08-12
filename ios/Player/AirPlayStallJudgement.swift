/// Pure decision for the AirPlay "silently stuck" recovery, kept free of
/// AVFoundation so it can be unit-tested off-device.
///
/// Over AirPlay, AVPlayer can drop `.waitingToPlayAtSpecifiedRate` (reason
/// `.noItemToPlay`) → `.paused` while the app still intends to play, leaving
/// playback stuck/silent with no error. Re-issuing `play()` on exactly that
/// transition recovers it. Scoped narrowly to AirPlay so it never nudges a
/// legitimate pause on the local route.
struct AirPlayStallJudgement {
  let previous: PlayerTimeControlStatus?
  let current: PlayerTimeControlStatus
  /// Whether the previous waiting state's reason was specifically
  /// `.noItemToPlay` (the AirPlay-stall signature, not a normal rebuffer).
  let previousWaitingReasonWasNoItemToPlay: Bool
  let isAirPlay: Bool
  let playWhenReady: Bool

  var shouldNudge: Bool {
    isAirPlay
      && playWhenReady
      && current == .paused
      && previous == .waitingToPlayAtSpecifiedRate
      && previousWaitingReasonWasNoItemToPlay
  }
}
