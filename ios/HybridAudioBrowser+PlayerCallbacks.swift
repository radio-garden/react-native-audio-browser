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
    // Hydrate the active track's `favorited` from the authoritative set so JS
    // consumers (useActiveTrack) match getActiveTrack()'s hydrated value — the
    // Player emits the raw queue track, whose flag isn't kept in sync.
    let hydrated = PlaybackActiveTrackChangedEvent(
      lastIndex: event.lastIndex,
      lastTrack: event.lastTrack,
      lastPosition: event.lastPosition,
      index: event.index,
      track: event.track.map(browserManager.hydrateFavorite),
    )
    activeTrackChangedEmitter.emit(hydrated)
  }

  public func playerDidUpdateProgress(_ event: PlaybackProgressUpdatedEvent) {
    onPlaybackProgressUpdated(event)
  }

  public func playerDidFirePlaybackInterval() {
    onPlaybackInterval()
  }

  public func playerDidChangePlayWhenReady(_ playWhenReady: Bool) {
    onPlaybackPlayWhenReadyChanged(PlaybackPlayWhenReadyChangedEvent(playWhenReady: playWhenReady))
  }

  public func playerDidChangePlayingState(_ state: PlayingState) {
    onPlaybackPlayingState(state)
  }

  public func playerDidEndQueue(_ event: PlaybackQueueEndedEvent) {
    onPlaybackQueueEnded(event)
  }

  public func playerDidChangeQueue(_ tracks: [Track]) {
    queueChangedEmitter.emit(tracks)
  }

  public func playerDidChangeRepeatMode(_ event: RepeatModeChangedEvent) {
    repeatModeChangedEmitter.emit(event)
  }

  public func playerDidChangeShuffleEnabled(_ enabled: Bool) {
    shuffleChangedEmitter.emit(enabled)
    onPlaybackShuffleModeChanged(enabled)
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

  public func remotePlay() {
    dispatchRemote(handler: handleRemotePlay, report: onRemotePlay) { player?.play() }
  }

  public func remotePause() {
    dispatchRemote(handler: handleRemotePause, report: onRemotePause) { player?.pause() }
  }

  public func remoteStop() {
    dispatchRemote(handler: handleRemoteStop, report: onRemoteStop) { player?.stop() }
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

  /// Runs JS's override for a remote command when it set one, else `fallback`, then reports
  /// the command to JS.
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
    // Apply the repeat mode change from CarPlay/lock screen
    try? setRepeatMode(mode: mode)
  }

  public func remoteChangeShuffleMode(enabled: Bool) {
    // Apply the shuffle mode change from CarPlay/lock screen
    try? setShuffleEnabled(enabled: enabled)
  }

  public func remoteChangePlaybackRate(rate: Float) {
    player?.rate = rate
  }
}
