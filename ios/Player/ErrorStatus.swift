#if canImport(NitroModules)
  import NitroModules
#endif

/// The player's error: none, one the retry loop is still working on (shown over a non-terminal
/// state, an Advisory error), or terminal (the `.error` state).
enum ErrorStatus: Equatable {
  case none
  case retrying(TrackPlayerError.PlaybackError)
  case terminal(TrackPlayerError.PlaybackError)

  var error: TrackPlayerError.PlaybackError? {
    switch self {
    case .none: nil
    case let .retrying(error), let .terminal(error): error
    }
  }

  var isRetrying: Bool {
    if case .retrying = self { true } else { false }
  }

  /// The JS-facing error, carrying the retrying flag.
  var nitroError: PlaybackError? {
    error?.toNitroError(retrying: isRetrying)
  }
}
