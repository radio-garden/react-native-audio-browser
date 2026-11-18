#if canImport(NitroModules)
  import NitroModules
#endif

/// What the Player publishes, in Nitro types; HybridAudioBrowser forwards it to JS.
@MainActor protocol PlayerCallbacks: AnyObject {
  func playerDidChangePlayback(_ playback: Playback)
  func playerDidChangeActiveTrack(_ event: PlaybackActiveTrackChangedEvent)
  func playerDidUpdateProgress(_ event: PlaybackProgressUpdatedEvent)
  /// Once a second while playing, when the playback interval is enabled.
  func playerDidFirePlaybackInterval()
  func playerDidChangePlayWhenReady(_ playWhenReady: Bool)
  func playerDidChangePlayingState(_ state: PlayingState)
  func playerDidEndQueue(_ event: PlaybackQueueEndedEvent)
  func playerDidChangeQueue(_ tracks: [Track])
  func playerDidChangeRepeatMode(_ event: RepeatModeChangedEvent)
  func playerDidChangeShuffleEnabled(_ enabled: Bool)
  /// Called on entering `.error`, on each new terminal error, and with a nil error on leaving it.
  func playerDidError(_ event: PlaybackErrorEvent)
  func playerDidReceiveTimedMetadata(_ metadata: TimedMetadata)
  func playerDidChangeSleepTimer(_ state: SleepTimerState?)
}
