/// Every trigger that can cause a playback state change.
/// Call sites describe what happened; the transition table decides the resulting state.
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
