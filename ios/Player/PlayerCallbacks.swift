#if canImport(NitroModules)
  import NitroModules
#endif

/// What the Player reports, in Nitro types. HybridAudioBrowser implements it.
@MainActor protocol PlayerCallbacks: AnyObject {
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
  func playerDidReceiveTimedMetadata(_ metadata: TimedMetadata)
  func playerDidChangeSleepTimer(_ state: SleepTimerState?)
}
