/// AVPlayer's time-control status, so the PlayerEngine seam carries no AVFoundation type.
enum PlayerTimeControlStatus {
  case paused, waitingToPlayAtSpecifiedRate, playing
}
