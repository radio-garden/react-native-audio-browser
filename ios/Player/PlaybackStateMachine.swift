/// Determines the next playback state for a given event, or `nil` to suppress the transition.
///
/// Guards here are **state-related** (e.g., "only from .loading"). Context-related
/// guards (e.g., `asset != nil`, `nearTrackEnd`, `!playWhenReady`) live at the call
/// site and decide whether to fire the event at all.
func nextPlaybackState(from current: PlaybackState, on event: PlaybackEvent) -> PlaybackState? {
  switch event {
  case .stopped:             return .stopped
  case .trackLoading:        return .loading
  case .trackUnloaded:       return PlaybackState.none
  case .trackEndedNaturally: return .ended
  case .avPlayerWaiting:     return .buffering
  case .avPlayerPlaying:     return .playing
  case .audioFrameDecoded:   return .playing
  case .errorOccurred:       return .error

  case .loadSeekCompleted:
    guard current == .loading else { return nil }
    return .ready

  case .avPlayerPaused(let hasAsset):
    guard current != .stopped else { return nil }
    if !hasAsset { return PlaybackState.none }
    guard current != .error else { return nil }
    return .paused

  case .bufferingSufficient:
    guard current != .playing else { return nil }
    return .ready
  }
}
