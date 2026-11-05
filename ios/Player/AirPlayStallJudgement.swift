/// Whether to re-issue `play()` for an AirPlay stall. Kept free of AVFoundation so it is
/// unit-testable off-device.
///
/// Over AirPlay, AVPlayer can go from `.waitingToPlayAtSpecifiedRate` (reason `.noItemToPlay`)
/// to `.paused` while the app still means to play, leaving playback silent with no error;
/// `play()` on exactly that transition recovers it. Limited to AirPlay so a real pause on the
/// local route is never nudged.
struct AirPlayStallJudgement {
  let previous: PlayerTimeControlStatus?
  let current: PlayerTimeControlStatus
  /// The previous waiting reason was `.noItemToPlay`: the stall's signature, not a rebuffer.
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
