/// The state an event leads to, or nil to leave the state as it is.
///
/// Guards here depend only on the current state. Guards on context (`asset != nil`,
/// `nearTrackEnd`, `!playWhenReady`) live at the call site, which decides whether to send the event.
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
  case .retryCancelled:
    guard current != .stopped, current != .ended, current != .error else { return nil }
    return .paused
  case .loadSeekCompleted:
    guard current == .loading else { return nil }
    return .ready
  case let .avPlayerPaused(hasAsset):
    guard current != .stopped else { return nil }
    if !hasAsset { return PlaybackState.none }
    // .error and .ended hold against stray pause observations; .ended sees them because the
    // natural-end intent clear opens the call site's `!playWhenReady` gate.
    guard current != .error, current != .ended else { return nil }
    return .paused
  case .bufferingSufficient:
    // A buffer refill must not move a terminal state back to .ready: .ended would restart the
    // finished track (playWhenReady is still true), and .stopped/.error would skip reload-on-play,
    // which starts only from those states. Leaving them takes an explicit load or reload.
    guard current != .playing, current != .ended, current != .stopped, current != .error
    else { return nil }
    return .ready
  }
}
