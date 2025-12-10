import Foundation

public enum RemoteCommand: CustomStringConvertible, Equatable {
  case play

  case pause

  case stop

  case togglePlayPause

  case next

  case previous

  case changePlaybackPosition

  case skipForward(preferredIntervals: [NSNumber])

  case skipBackward(preferredIntervals: [NSNumber])

  case like(isActive: Bool, localizedTitle: String, localizedShortTitle: String)

  case dislike(isActive: Bool, localizedTitle: String, localizedShortTitle: String)

  case bookmark(isActive: Bool, localizedTitle: String, localizedShortTitle: String)

  case changeRepeatMode

  case changeShuffleMode

  case changePlaybackRate(supportedPlaybackRates: [NSNumber])

  public var description: String {
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
    case .like: "like"
    case .dislike: "dislike"
    case .bookmark: "bookmark"
    case .changeRepeatMode: "changeRepeatMode"
    case .changeShuffleMode: "changeShuffleMode"
    case .changePlaybackRate: "changePlaybackRate"
    }
  }

  var key: String { description }

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
      .like(isActive: false, localizedTitle: "", localizedShortTitle: ""),
      .dislike(isActive: false, localizedTitle: "", localizedShortTitle: ""),
      .bookmark(isActive: false, localizedTitle: "", localizedShortTitle: ""),
      .changeRepeatMode,
      .changeShuffleMode,
      .changePlaybackRate(supportedPlaybackRates: []),
    ]
  }
}
