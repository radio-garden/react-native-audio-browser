/// Determines the next playback state for a given event, or `nil` to suppress the transition.
///
/// Guards here are **state-related** (e.g., "only from .loading"). Context-related
/// guards (e.g., `asset != nil`, `nearTrackEnd`, `!playWhenReady`) live at the call
/// site and decide whether to fire the event at all.
func nextPlaybackState(from current: PlaybackState, on event: PlaybackEvent) -> PlaybackState? {
  switch event {
  case .stopped: return .stopped
  case .trackLoading: return .loading
  case .trackUnloaded: return PlaybackState.none
  case .trackEndedNaturally: return .ended
  case .avPlayerWaiting: return .buffering
  case .avPlayerPlaying: return .playing
  case .audioFrameDecoded: return .playing
  case .errorOccurred: return .error
  case .loadSeekCompleted:
    guard current == .loading else { return nil }
    return .ready
  case let .avPlayerPaused(hasAsset):
    guard current != .stopped else { return nil }
    if !hasAsset { return PlaybackState.none }
    // .error and .ended own their state against stray pause observations —
    // reachable from .ended since the natural-end intent clear opens the
    // call site's `!playWhenReady` gate.
    guard current != .error, current != .ended else { return nil }
    return .paused
  case .bufferingSufficient:
    // Terminal states must not drift back to .ready on a buffer refill: .ended
    // would restart the completed track (playWhenReady is still true), and
    // .stopped/.error would skip the reload-on-play path, which only triggers
    // from those states. Leaving them requires an explicit load or reload.
    guard current != .playing, current != .ended, current != .stopped, current != .error
    else { return nil }
    return .ready
  }
}
