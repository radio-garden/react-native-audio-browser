import Foundation

/// A system remote command (lock screen, Control Center, CarPlay, headset). Skip intervals and
/// playback rates are passed to `RemoteCommandController.enable` beside the commands.
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
