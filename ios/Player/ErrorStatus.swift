#if canImport(NitroModules)
  import NitroModules
#endif

/// The player's error: none, an advisory error while retrying, or the terminal error.
enum ErrorStatus: Equatable {
  case none
  case retrying(TrackPlayerError.PlaybackError)
  /// `report` counts terminal errors, so a repeat of the same error still publishes.
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

  var nitroError: PlaybackError? {
    error?.toNitroError(retrying: isRetrying)
  }

  /// The status once `event` has moved the state from `old` to `new`.
  func after(event: PlaybackEvent, from old: PlaybackState, to new: PlaybackState) -> ErrorStatus {
    if case let .errorOccurred(error) = event {
      return .terminal(error, report: terminalReport &+ 1)
    }
    if case .retryCancelled = event { return .none }
    if old == .error || (isRetrying && !new.carriesAdvisoryError) { return .none }
    return self
  }

  private var terminalReport: UInt {
    if case let .terminal(_, report) = self { report } else { 0 }
  }
}

extension PlaybackState {
  var carriesAdvisoryError: Bool {
    self == .loading || self == .buffering || self == .paused
  }

  var awaitsData: Bool {
    self == .loading || self == .buffering
  }
}
