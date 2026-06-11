#if canImport(NitroModules)
  import NitroModules
#endif

/// Subset of TrackPlayerCallbacks containing only the callbacks the coordinator fires.
/// Uses Nitro types (no AVFoundation). TrackPlayerCallbacks extends this protocol
/// so HybridAudioBrowser implements both automatically.
@MainActor protocol PlaybackCoordinatorCallbacks: AnyObject {
  func playerDidChangePlayback(_ playback: Playback)
  func playerDidChangeActiveTrack(_ event: PlaybackActiveTrackChangedEvent)
  func playerDidUpdateProgress(_ event: PlaybackProgressUpdatedEvent)
  func playerDidFirePlaybackInterval()
  func playerDidChangePlayWhenReady(_ playWhenReady: Bool)
  func playerDidChangePlayingState(_ state: PlayingState)
  func playerDidEndQueue(_ event: PlaybackQueueEndedEvent)
  func playerDidChangeQueue(_ tracks: [Track])
  func playerDidChangeRepeatMode(_ event: RepeatModeChangedEvent)
  func playerDidChangeShuffleEnabled(_ enabled: Bool)
  func playerDidError(_ event: PlaybackErrorEvent)
}
