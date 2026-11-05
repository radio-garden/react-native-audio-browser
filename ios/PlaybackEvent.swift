/// What happened to playback; `nextPlaybackState` decides the resulting state, and the call site
/// decides whether to fire the event at all.
enum PlaybackEvent {
  case stopped
  case trackLoading
  case trackUnloaded
  case trackEndedNaturally
  case loadSeekCompleted
  case avPlayerPaused(hasAsset: Bool)
  case avPlayerWaiting
  case avPlayerPlaying
  case audioFrameDecoded
  case bufferingSufficient
  case errorOccurred(TrackPlayerError.PlaybackError)
}
