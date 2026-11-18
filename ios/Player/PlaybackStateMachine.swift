/// The state `event` moves `current` to, or nil when the event does not apply in `current`.
/// Same table as `nextPlaybackState` on web and `PlaybackStateMachine.transitions` on Android
/// (ADR 0003): change all three together. Guards here read only the state; guards on context
/// belong at the call site that sends the event.
func nextPlaybackState(from current: PlaybackState, on event: PlaybackEvent) -> PlaybackState? {
  switch event {
  case .stopped: return .stopped
  case .trackLoading: return .loading
  case .trackUnloaded: return PlaybackState.none
  case .trackEndedNaturally: return .ended
  // An item still connecting when the retry gave up can report late; leaving .error takes a reload.
  case .avPlayerWaiting: return current == .error ? nil : .buffering
  case .avPlayerPlaying, .audioFrameDecoded: return current == .error ? nil : .playing
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
    // Reaches .ended too: the natural end clears the play intent, opening the call site's gate.
    guard current != .error, current != .ended else { return nil }
    return .paused
  case .bufferingSufficient:
    // From .ended, .ready would restart the finished track; .stopped and .error reload on play.
    guard current != .playing, current != .ended, current != .stopped, current != .error
    else { return nil }
    return .ready
  }
}
