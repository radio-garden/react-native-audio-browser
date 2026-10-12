/// Decouples Player from AVFoundation enum types.
/// AVPlayerEngine maps from AVPlayer.TimeControlStatus → PlayerTimeControlStatus at the call boundary.
enum PlayerTimeControlStatus {
  case paused, waitingToPlayAtSpecifiedRate, playing
}
