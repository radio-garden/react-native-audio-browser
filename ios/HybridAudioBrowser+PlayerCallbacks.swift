import AVFoundation
import AVKit
import Foundation
import MediaPlayer
import NitroModules
import os.log

// MARK: - PlayerCallbacks, AVPlayerEngineCallbacks

extension HybridAudioBrowser: PlayerCallbacks, AVPlayerEngineCallbacks {
  public func playerDidChangePlayback(_ playback: Playback) {
    onPlaybackChanged(playback)
  }

  public func playerDidChangeActiveTrack(_ event: PlaybackActiveTrackChangedEvent) {
    // A queued track that carries no `favorited` is hydrated here, as getActiveTrack() does.
    let hydrated = PlaybackActiveTrackChangedEvent(
      lastIndex: event.lastIndex,
      lastTrack: event.lastTrack,
      lastPosition: event.lastPosition,
      index: event.index,
      track: event.track.map(browserManager.hydrateFavorite),
    )
    onPlaybackActiveTrackChanged(hydrated)
    activeTrackChangedEmitter.emit(hydrated)
  }

  public func playerDidUpdateProgress(_ event: PlaybackProgressUpdatedEvent) {
    onPlaybackProgressUpdated(event)
  }

  public func playerDidFirePlaybackInterval() {
    onPlaybackInterval()
  }

  public func playerDidChangePlayWhenReady(_: Bool) {
    stagedPlayerState.announce()
  }

  public func playerDidChangePlayingState(_ state: PlayingState) {
    onPlaybackPlayingState(state)
  }

  public func playerDidEndQueue(_ event: PlaybackQueueEndedEvent) {
    onPlaybackQueueEnded(event)
  }

  public func playerDidChangeQueue(_ tracks: [Track]) {
    onPlaybackQueueChanged(tracks)
    queueChangedEmitter.emit(tracks)
  }

  public func playerDidChangeRepeatMode(_: RepeatModeChangedEvent) {
    stagedPlayerState.announce()
  }

  public func playerDidChangeShuffleEnabled(_ enabled: Bool) {
    onPlaybackShuffleModeChanged(enabled)
    shuffleChangedEmitter.emit(enabled)
  }

  public func playerDidError(_ event: PlaybackErrorEvent) {
    onPlaybackError(event)
  }

  public func playerDidChangeSleepTimer(_ state: SleepTimer?) {
    onSleepTimerChanged(state)
  }

  public func playerDidChangeNowPlaying(_ metadata: NowPlayingMetadata) {
    onNowPlayingChanged(metadata)
  }

  public func playerDidReceiveCommonMetadata(_ items: [AVMetadataItem]) {
    let metadata = TrackMetadata.from(items: items)
    onTrackMetadata(metadata)
  }

  public func playerDidReceiveTimedMetadata(_ metadata: TimedMetadata) {
    onTimedMetadata(metadata)
  }

  public func playerDidReceiveChapterMetadata(_ groups: [AVTimedMetadataGroup]) {
    let chapters = ChapterMetadata.from(groups: groups)
    onChapterMetadata(chapters)
  }

  var remoteIsPlaying: Bool {
    stagedPlayerState.isPlaying()
  }

  public func remotePlay() {
    dispatchRemote(handler: handleRemotePlay, report: onRemotePlay) { stagedPlayerState.setPlayWhenReady(true) }
  }

  public func remotePause() {
    dispatchRemote(handler: handleRemotePause, report: onRemotePause) { stagedPlayerState.setPlayWhenReady(false) }
  }

  public func remoteStop() {
    dispatchRemote(handler: handleRemoteStop, report: onRemoteStop) { stagedPlayerState.stopFromRemote() }
  }

  public func remoteNext() {
    dispatchRemote(handler: handleRemoteNext, report: onRemoteNext) { player?.next() }
  }

  public func remotePrevious() {
    dispatchRemote(handler: handleRemotePrevious, report: onRemotePrevious) { player?.previous() }
  }

  public func remoteJumpForward(interval: Double) {
    dispatchRemote(
      RemoteJumpForwardEvent(interval: interval), handler: handleRemoteJumpForward, report: onRemoteJumpForward,
    ) { player?.seek(by: interval) }
  }

  public func remoteJumpBackward(interval: Double) {
    dispatchRemote(
      RemoteJumpBackwardEvent(interval: interval), handler: handleRemoteJumpBackward, report: onRemoteJumpBackward,
    ) { player?.seek(by: -interval) }
  }

  public func remoteSeek(position: Double) {
    dispatchRemote(RemoteSeekEvent(position: position), handler: handleRemoteSeek, report: onRemoteSeek) {
      player?.seek(to: position)
    }
  }

  /// Runs JS's handler for a remote command if it set one, else `fallback`; reports the command
  /// to JS either way.
  private func dispatchRemote(handler: (() -> Void)?, report: () -> Void, fallback: () -> Void) {
    if let handler { handler() } else { fallback() }
    report()
  }

  private func dispatchRemote<Event>(
    _ event: Event,
    handler: ((Event) -> Void)?,
    report: (Event) -> Void,
    fallback: () -> Void,
  ) {
    if let handler { handler(event) } else { fallback() }
    report(event)
  }

  public func remoteChangeRepeatMode(mode: RepeatMode) {
    try? setRepeatMode(mode: mode)
  }

  public func remoteChangeShuffleMode(enabled: Bool) {
    try? setShuffleEnabled(enabled: enabled)
  }

  public func remoteChangePlaybackRate(rate: Float) {
    player?.rate = rate
  }
}
