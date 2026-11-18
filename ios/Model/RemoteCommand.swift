import Foundation

enum RemoteCommand: CaseIterable, Hashable {
  case play
  case pause
  case stop
  case next
  case previous
  case changePlaybackPosition
  case skipForward
  case skipBackward
  case changeRepeatMode
  case changeShuffleMode
  case changePlaybackRate
}
