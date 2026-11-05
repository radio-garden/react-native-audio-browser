/// AVPlayer's time-control status, mirrored so the Player and its tests stay free of AVFoundation.
enum PlayerTimeControlStatus {
  case paused, waitingToPlayAtSpecifiedRate, playing
}
