import Foundation

// Internal on purpose: nothing outside the module uses this, and a public
// declaration gets printed into the generated -Swift.h C++ interop section,
// where the [NSNumber] associated values don't compile for plain-C++
// consumers of the header (Xcode 26.2 / Swift 6.2).
enum RemoteCommand: CustomStringConvertible, Equatable {
  case play

  case pause

  case stop

  case togglePlayPause

  case next

  case previous

  case changePlaybackPosition

  case skipForward(preferredIntervals: [NSNumber])

  case skipBackward(preferredIntervals: [NSNumber])

  case changeRepeatMode

  case changeShuffleMode

  case changePlaybackRate(supportedPlaybackRates: [NSNumber])

  var description: String {
    switch self {
    case .play: "play"
    case .pause: "pause"
    case .stop: "stop"
    case .togglePlayPause: "togglePlayPause"
    case .next: "nextTrack"
    case .previous: "previousTrack"
    case .changePlaybackPosition: "changePlaybackPosition"
    case .skipForward: "skipForward"
    case .skipBackward: "skipBackward"
    case .changeRepeatMode: "changeRepeatMode"
    case .changeShuffleMode: "changeShuffleMode"
    case .changePlaybackRate: "changePlaybackRate"
    }
  }

  var key: String { description }

  /**
   Commands in `self` that are absent from `next` and so should be disabled.

   Compared by `key`, not by `==`. `RemoteCommand` is `Equatable` including its
   associated values, but every case with associated values maps onto a single
   `MPRemoteCommand` addressed by `key`: `.skipForward([15])` and
   `.skipForward([30])` are two different values of one command. Diffing by `==`
   would report the old interval as removed and disable the command that the new
   interval had just re-enabled.
   */
  static func commandsToDisable(
    enabled: [RemoteCommand],
    replacedBy next: [RemoteCommand],
  ) -> [RemoteCommand] {
    let nextKeys = Set(next.map(\.key))
    return enabled.filter { !nextKeys.contains($0.key) }
  }

  /**
   All values in an array for convenience.
   Don't use for associated values.
   */
  static func all() -> [RemoteCommand] {
    [
      .play,
      .pause,
      .stop,
      .togglePlayPause,
      .next,
      .previous,
      .changePlaybackPosition,
      .skipForward(preferredIntervals: []),
      .skipBackward(preferredIntervals: []),
      .changeRepeatMode,
      .changeShuffleMode,
      .changePlaybackRate(supportedPlaybackRates: []),
    ]
  }
}
