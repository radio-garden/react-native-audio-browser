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
  /// A pause cancelled a pending retry.
  case retryCancelled
}

extension PlaybackEvent {
  /// The event for a public log line: no URL, title or track. A failure omits the URL it may carry.
  var logDescription: String {
    switch self {
    case .stopped: "stopped"
    case .trackLoading: "trackLoading"
    case .trackUnloaded: "trackUnloaded"
    case .trackEndedNaturally: "trackEndedNaturally"
    case .loadSeekCompleted: "loadSeekCompleted"
    case let .avPlayerPaused(hasAsset): "avPlayerPaused(hasAsset: \(hasAsset))"
    case .avPlayerWaiting: "avPlayerWaiting"
    case .avPlayerPlaying: "avPlayerPlaying"
    case .audioFrameDecoded: "audioFrameDecoded"
    case .bufferingSufficient: "bufferingSufficient"
    case .errorOccurred: "errorOccurred"
    case .retryCancelled: "retryCancelled"
    }
  }
}
