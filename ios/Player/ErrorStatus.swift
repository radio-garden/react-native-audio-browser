#if canImport(NitroModules)
  import NitroModules
#endif

/// The player's error: none, one the retry loop is still working on (shown over a non-terminal
/// state, an Advisory error), or terminal (the `.error` state).
enum ErrorStatus: Equatable {
  case none
  case retrying(TrackPlayerError.PlaybackError)
  /// `report` tells each terminal report from the last, so a repeat of the same error is a change.
  case terminal(TrackPlayerError.PlaybackError, report: UInt)

  var error: TrackPlayerError.PlaybackError? {
    switch self {
    case .none: nil
    case let .retrying(error), let .terminal(error, _): error
    }
  }

  var isRetrying: Bool {
    if case .retrying = self { true } else { false }
  }

  /// The JS-facing error, carrying the retrying flag.
  var nitroError: PlaybackError? {
    error?.toNitroError(retrying: isRetrying)
  }

  /// The status once `event` moves the state from `old` to `new`: an error event is a new
  /// terminal report; leaving `.error`, or reaching a state without an advisory error, clears it.
  func after(event: PlaybackEvent, from old: PlaybackState, to new: PlaybackState) -> ErrorStatus {
    if case let .errorOccurred(error) = event {
      return .terminal(error, report: terminalReport &+ 1)
    }
    if old == .error || (isRetrying && !new.carriesAdvisoryError) { return .none }
    return self
  }

  private var terminalReport: UInt {
    if case let .terminal(_, report) = self { report } else { 0 }
  }
}

extension PlaybackState {
  /// Whether a retrying error shows over this state: the listener is waiting on it, and no audio
  /// is playing to contradict it.
  var carriesAdvisoryError: Bool {
    self == .loading || self == .buffering || self == .paused
  }
}
