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
  // The AVPlayer's own events can land after a stop, an end or a failure.
  case .avPlayerWaiting: return current.isTerminal ? nil : .buffering
  case .avPlayerPlaying, .audioFrameDecoded: return current.isTerminal ? nil : .playing
  case .errorOccurred: return .error
  case .retryCancelled:
    guard !current.isTerminal else { return nil }
    return .paused
  case .loadSeekCompleted:
    guard current == .loading else { return nil }
    return .ready
  case let .avPlayerPaused(hasAsset):
    guard current != .stopped else { return nil }
    // .error and .ended hold against stray pause observations; .ended sees them because the
    // natural-end intent clear opens the call site's `!playWhenReady` gate.
    guard current != .error, current != .ended else { return nil }
    return hasAsset ? .paused : PlaybackState.none
  case .bufferingSufficient:
    // .ended still has the play intent: a move to .ready would restart the finished track.
    guard current != .playing, !current.isTerminal else { return nil }
    return .ready
  }
}

extension PlaybackState {
  /// Stopped, ended or failed: play() reloads from it, and only an explicit load, through
  /// .loading, leaves it. The AVPlayer's own events and a cancelled retry cannot.
  var isTerminal: Bool { self == .stopped || self == .ended || self == .error }
}
