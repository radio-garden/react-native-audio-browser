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
    // The Player sets the error before this fires, so the error line renders.
    engine?.nowPlayingUpdater.render()
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
    engine?.nowPlayingUpdater.trackChanged()
  }

  public func playerDidUpdateProgress(_ event: PlaybackProgressUpdatedEvent) {
    onPlaybackProgressUpdated(event)
  }

  public func playerDidFirePlaybackInterval() {
    onPlaybackInterval()
  }

  public func playerDidChangePlayWhenReady(_ playWhenReady: Bool) {
    // Intent to play again is the earliest cancel signal for a pending session release.
    if playWhenReady { audioSession.cancelRelease() }
    onPlaybackPlayWhenReadyChanged(PlaybackPlayWhenReadyChangedEvent(playWhenReady: playWhenReady))
  }

  public func playerDidChangePlayingState(_ state: PlayingState) {
    onPlaybackPlayingState(state)
    // Lazily activate the audio session the moment playback actually produces — or is buffering to
    // produce — output. This (not setup) is where we grab the session, so launching the app never
    // interrupts other apps' audio; the interruption only happens when the user truly starts
    // playback, which is correct. Keyed off play *state*, not `playWhenReady`, because the intent
    // can be set at setup (before any track) while output only begins here. Idempotent:
    // `setActive(true)` is a no-op when the session is already active.
    if state.playing || state.buffering {
      // The now-playing session's activation request at link time predates this
      // (the audio session was still inactive, and the system may have declined);
      // re-request once the audio session is active, so the surface can appear even if
      // playback never produces audio (first-ever load failing on a dead stream).
      audioSession.activate { [weak self] in self?.engine?.reactivateNowPlayingSession() }
    }
    // A stall starting or recovering may not change the Player state.
    engine?.nowPlayingUpdater.render()
  }

  public func playerShouldReleaseSession() {
    audioSession.scheduleRelease()
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

  public func playerDidReceiveTimedMetadata(_ groups: [AVTimedMetadataGroup]) {
    for group in groups {
      if let metadata = TimedMetadata.from(items: group.items, icyCharset: player?.currentTrack?.icyCharset) {
        onTimedMetadata(metadata)
        engine?.nowPlayingUpdater.receivedTimedMetadata(metadata)
      }
    }
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

  public func remotePlayPause() {
    if player?.playWhenReady == true { remotePause() } else { remotePlay() }
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
