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

  public var description: String {
    switch self {
    case .play: return "play"
    case .pause: return "pause"
    case .stop: return "stop"
    case .togglePlayPause: return "togglePlayPause"
    case .next: return "nextTrack"
    case .previous: return "previousTrack"
    case .changePlaybackPosition: return "changePlaybackPosition"
    case .skipForward: return "skipForward"
    case .skipBackward: return "skipBackward"
    case .like: return "like"
    case .dislike: return "dislike"
    case .bookmark: return "bookmark"
    }
  }

  var key: String { description }

  /**
   All values in an array for convenience.
   Don't use for associated values.
   */
  static func all() -> [RemoteCommand] {
    return [
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
    ]
  }
}
