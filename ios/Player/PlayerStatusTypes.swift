/// Decouples PlaybackCoordinator from AVFoundation enum types.
/// TrackPlayer maps from AVPlayer.TimeControlStatus → PlayerTimeControlStatus at the call boundary.
enum PlayerTimeControlStatus {
  case paused, waitingToPlayAtSpecifiedRate, playing
}
