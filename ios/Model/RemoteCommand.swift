import Foundation

/// A system remote command (lock screen, Control Center, CarPlay, headset). The skip intervals
/// and the playback rates the commands offer go to `RemoteCommandController.enable` beside them.
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
