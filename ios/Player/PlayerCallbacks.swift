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
  /// Playback is deliberately stopped — play intent is off, it isn't an interruption-driven pause
  /// (those resume), and no retry is pending (a live-stream reconnect must keep the session). The
  /// host may release the audio session (debounced) so other apps' audio can resume.
  func playerShouldReleaseSession()
  func playerDidChangeSleepTimer(_ state: SleepTimerState?)
}
