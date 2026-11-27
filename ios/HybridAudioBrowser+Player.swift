import AVFoundation
import AVKit
import Foundation
import MediaPlayer
import NitroModules
import os.log

public extension HybridAudioBrowser {
  // MARK: - Playback Control

  func load(track: Track) throws {
    try onMainActor {
      guard let player else {
        throw NSError(domain: "AudioBrowser", code: 1, userInfo: [NSLocalizedDescriptionKey: "Player not initialized"])
      }
      try player.load(track)
    }
  }

  func reset() throws {
    onMainActor { player?.clear() }
  }

  func play() throws {
    onMainActor { stagedPlayerState.setPlayWhenReady(true) }
  }

  func pause() throws {
    onMainActor { stagedPlayerState.setPlayWhenReady(false) }
  }

  func togglePlayback() throws {
    onMainActor { stagedPlayerState.togglePlayback() }
  }

  func stop() throws {
    onMainActor { stagedPlayerState.stop() }
  }

  func setPlayWhenReady(playWhenReady: Bool) throws {
    onMainActor { stagedPlayerState.setPlayWhenReady(playWhenReady) }
  }

  func getPlayWhenReady() throws -> Bool {
    onMainActor { stagedPlayerState.playWhenReady() }
  }

  func seekTo(position: Double) throws {
    guard let position = finite(position, "seekTo") else { return }
    onMainActor { player?.seek(to: position) }
  }

  func seekBy(offset: Double) throws {
    guard let offset = finite(offset, "seekBy") else { return }
    onMainActor { player?.seek(by: offset) }
  }

  private func finite(_ seconds: Double, _ method: String) -> Double? {
    guard seconds.isFinite else {
      logger.error("\(method, privacy: .public) ignored a non-finite time: \(seconds)")
      return nil
    }
    return seconds
  }

  func seekToLiveEdge() throws {
    onMainActor { player?.seekToLiveEdge() }
  }

  func setVolume(level: Double) throws {
    guard let volume = clampedVolume(level) else {
      logger.error("setVolume ignored a NaN level")
      return
    }
    onMainActor { engine?.volume = volume }
  }

  func getVolume() throws -> Double {
    onMainActor { Double(engine?.volume ?? 1.0) }
  }

  func setRate(rate: Double) throws {
    onMainActor { player?.rate = Float(rate) }
  }

  func getRate() throws -> Double {
    onMainActor { Double(player?.rate ?? 1.0) }
  }

  func getProgress() throws -> Progress {
    onMainActor {
      Progress(
        position: player?.currentTime ?? 0,
        duration: player?.duration ?? 0,
        buffered: player?.bufferedPosition ?? 0,
      )
    }
  }

  func getPlayback() throws -> Playback {
    onMainActor { player?.getPlayback() ?? Playback(state: .none, error: nil) }
  }

  func getPlayingState() throws -> PlayingState {
    onMainActor {
      player?.playingState ?? PlayingState(playing: false, buffering: false)
    }
  }

  func getRepeatMode() throws -> RepeatMode {
    onMainActor { stagedPlayerState.repeatMode() }
  }

  func setRepeatMode(mode: RepeatMode) throws {
    onMainActor { stagedPlayerState.setRepeatMode(mode) }
  }

  func getShuffleEnabled() throws -> Bool {
    onMainActor { player?.shuffleEnabled ?? false }
  }

  func setShuffleEnabled(enabled: Bool) throws {
    onMainActor { player?.shuffleEnabled = enabled }
  }

  func getPlaybackError() throws -> PlaybackError? {
    onMainActor { player?.nitroPlaybackError }
  }

  func retry() throws {
    // Re-resolves rather than replaying the cached URL: short-lived URLs or tokens may have
    // expired since the failure.
    onMainActor { player?.retryNow() }
  }

  // MARK: - Sleep Timer

  func getSleepTimer() throws -> SleepTimer {
    onMainActor {
      player?.sleepTimerManager.get() ?? .first(NullType.null)
    }
  }

  func setSleepTimer(seconds: Double, fadeDuration: Double?) throws {
    onMainActor {
      player?.sleepTimerManager.set(seconds: seconds, fadeDuration: fadeDuration)
    }
  }

  func setSleepTimerToEndOfTrack() throws {
    onMainActor {
      player?.sleepTimerManager.setToEndOfTrack()
    }
  }

  func clearSleepTimer() throws -> Bool {
    onMainActor {
      player?.sleepTimerManager.clear() ?? false
    }
  }

  // MARK: - Queue Management

  func add(tracks: [Track], insertBeforeIndex: Double?) throws {
    try onMainActor {
      guard let player else { return }
      if let index = insertBeforeIndex {
        try player.add(tracks, at: Int(queueIndex: index))
      } else {
        try player.add(tracks)
      }
    }
  }

  func move(fromIndex: Double, toIndex: Double) throws {
    try onMainActor {
      try player?.move(fromIndex: Int(queueIndex: fromIndex), toIndex: Int(queueIndex: toIndex))
    }
  }

  func remove(indexes: [Double]) throws {
    try onMainActor {
      guard let player else { return }
      // Highest index first, so earlier removals don't shift the ones still to go.
      for index in try indexes.map({ try Int(queueIndex: $0) }).sorted().reversed() {
        try player.remove(index)
      }
    }
  }

  func removeUpcomingTracks() throws {
    onMainActor { player?.removeUpcomingTracks() }
  }

  func skip(index: Double, initialPosition: Double?) throws {
    try onMainActor {
      try player?.skipTo(Int(queueIndex: index))
      if let position = initialPosition.flatMap({ finite($0, "skip") }) {
        player?.seek(to: position)
      }
    }
  }

  func skipToNext(initialPosition: Double?) throws {
    onMainActor {
      player?.next()
      if let position = initialPosition.flatMap({ finite($0, "skipToNext") }) {
        player?.seek(to: position)
      }
    }
  }

  func skipToPrevious(initialPosition: Double?) throws {
    onMainActor {
      player?.previous()
      if let position = initialPosition.flatMap({ finite($0, "skipToPrevious") }) {
        player?.seek(to: position)
      }
    }
  }

  func setActiveTrackFavorited(favorited: Bool) throws {
    favoriteActiveTrack(favorited)
  }

  /// Sets the active track's favorite state in the favorite set, which restamps every copy of
  /// the track, on screen and queued. False when nothing is playing or the track has no
  /// identity.
  @discardableResult
  internal func favoriteActiveTrack(_ favorited: Bool) -> Bool {
    onMainActor {
      guard let player, let track = player.currentTrack, let identity = track.identity else { return false }
      browserManager.setFavorited(identity: identity, favorited: favorited)
      let updated = player.currentTrack ?? track
      // The only emit: a favorite toggle mutates the active track in place, so
      // onPlaybackActiveTrackChanged stays transition-only.
      let event = FavoriteChangedEvent(track: updated, favorited: favorited)
      onFavoriteChanged(event)
      favoriteChangedEmitter.emit(event)
      return true
    }
  }

  internal func isActiveTrackFavorited() -> Bool {
    (try? getActiveTrack())?.favorited ?? false
  }

  func toggleActiveTrackFavorited() throws {
    favoriteActiveTrack(!isActiveTrackFavorited())
  }

  func setQueue(tracks: [Track], startIndex: Double?, startPosition: Double?) throws {
    try onMainActor {
      guard let player else { return }
      try player.setQueue(
        tracks,
        initialIndex: startIndex.map { try Int(queueIndex: $0) } ?? 0,
        startPositionMs: startPosition.flatMap { finite($0, "setQueue") }.map { $0 * 1000 },
      )
    }
  }

  func getQueue() throws -> [Track] {
    onMainActor { player?.tracks ?? [] }
  }

  func getQueueVersion() throws -> Double {
    onMainActor { Double(player?.queueVersion ?? -1) }
  }

  func getTrack(index: Double) throws -> Track? {
    onMainActor {
      guard let tracks = player?.tracks, let i = try? Int(queueIndex: index) else { return nil }
      guard i >= 0, i < tracks.count else { return nil }
      return tracks[i]
    }
  }

  func getActiveTrackIndex() throws -> Double? {
    onMainActor {
      guard let index = player?.queue.currentIndex, index >= 0 else { return nil }
      return Double(index)
    }
  }

  func getActiveTrack() throws -> Track? {
    // A queued track that carries no `favorited` (a queue set from JS) is filled from the
    // favorite set; one that carries it is kept current by `setFavorites` and the heart toggle.
    onMainActor { player?.currentTrack.map(browserManager.hydrateFavorite) }
  }

  // MARK: - Now Playing

  func updateNowPlaying(update: NowPlayingUpdate?) throws {
    onMainActor { engine?.nowPlayingUpdater.setOverride(update) }
  }

  func flashNowPlaying(update: NowPlayingUpdate, durationMs: Double) throws {
    onMainActor { engine?.nowPlayingUpdater.flash(update, durationMs: durationMs) }
  }

  func clearNowPlayingFlash() throws {
    onMainActor { engine?.nowPlayingUpdater.clearFlash() }
  }

  func getNowPlaying() throws -> NowPlayingMetadata? {
    onMainActor { engine?.nowPlayingUpdater.nowPlaying }
  }

  func getTimedMetadata() throws -> TimedMetadata? {
    onMainActor { engine?.nowPlayingUpdater.timedMetadata }
  }

  // MARK: - Network

  func getOnline() throws -> Bool {
    // No `onMainActor` hop: `isOnline` is lock-protected, so this stays a cheap read on the
    // JS thread instead of blocking on the main queue.
    networkMonitor.isOnline
  }

  // MARK: - System Volume

  func getSystemVolume() throws -> Double {
    AudioSessionController.volume
  }

  func setSystemVolume(volume _: Double) throws {
    logger.debug("setSystemVolume is not supported on iOS - volume must be adjusted via hardware buttons or Control Center")
  }

  // MARK: - External Audio Output

  func getOutput() throws -> Output? {
    AudioSessionController.currentOutput
  }

  func supportsOutputSwitcher() throws -> Bool {
    // iOS can always present the system route picker (AVRoutePickerView).
    true
  }

  func openOutputPicker() throws {
    DispatchQueue.main.async { OutputPicker.present() }
  }
}
