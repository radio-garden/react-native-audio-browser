/// Whether to issue `play()` again because AVPlayer, over AirPlay, went from a `.noItemToPlay`
/// wait to `.paused` while the intent is to play, leaving playback silent with no error. Limited
/// to AirPlay so a real pause on the local route is never undone.
struct AirPlayStallJudgement {
  let previous: PlayerTimeControlStatus?
  let current: PlayerTimeControlStatus
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
