import AVFoundation
import AVKit
import Foundation
import MediaPlayer
import NitroModules
import os.log

public extension HybridAudioBrowser {
  private func warnDropped(_ message: String) {
    logger.warning("\(message, privacy: .public)")
  }

  /// Runs `command` on the player; before setup it is dropped with a warning.
  private func commandPlayer(_ name: String, _ command: @MainActor (Player) throws -> Void) rethrows {
    try onMainActor {
      try runPlayerCommand(name, on: player, warn: warnDropped) { try command($0) }
    }
  }

  private func commandEngine(_ name: String, _ command: @MainActor (AVPlayerEngine) -> Void) {
    onMainActor {
      runPlayerCommand(name, on: engine, warn: warnDropped) { command($0) }
    }
  }

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
    commandPlayer("reset") { $0.clear() }
  }

  func play() throws {
    commandPlayer("play") { $0.play() }
  }

  func pause() throws {
    commandPlayer("pause") { $0.pause() }
  }

  func togglePlayback() throws {
    commandPlayer("togglePlayback") { $0.togglePlayback() }
  }

  func stop() throws {
    commandPlayer("stop") { $0.stop() }
  }

  func setPlayWhenReady(playWhenReady: Bool) throws {
    onMainActor {
      if let player { player.setPlayWhenReady(playWhenReady) }
      else { pending.playWhenReady = playWhenReady }
    }
  }

  func getPlayWhenReady() throws -> Bool {
    onMainActor { player?.playWhenReady ?? pending.playWhenReady ?? false }
  }

  func seekTo(position: Double) throws {
    guard let position = finite(position, "seekTo") else { return }
    commandPlayer("seekTo") { $0.seek(to: position) }
  }

  func seekBy(offset: Double) throws {
    guard let offset = finite(offset, "seekBy") else { return }
    commandPlayer("seekBy") { $0.seek(by: offset) }
  }

  private func finite(_ seconds: Double, _ method: String) -> Double? {
    guard seconds.isFinite else {
      logger.error("\(method, privacy: .public) ignored a non-finite time: \(seconds)")
      return nil
    }
    return seconds
  }

  func seekToLiveEdge() throws {
    commandPlayer("seekToLiveEdge") { $0.seekToLiveEdge() }
  }

  func setVolume(level: Double) throws {
    commandEngine("setVolume") { $0.volume = Float(level) }
  }

  func getVolume() throws -> Double {
    onMainActor { Double(engine?.volume ?? 1.0) }
  }

  func setRate(rate: Double) throws {
    commandPlayer("setRate") { $0.rate = Float(rate) }
  }

  func getRate() throws -> Double {
    onMainActor { Double(player?.rate ?? 1.0) }
  }

  func getProgress() throws -> Progress {
    onMainActor {
      Progress(
        position: player?.currentTime ?? 0,
        duration: engine?.duration ?? 0,
        buffered: engine?.bufferedPosition ?? 0,
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
    onMainActor { player?.repeatMode ?? pending.repeatMode ?? .off }
  }

  func setRepeatMode(mode: RepeatMode) throws {
    onMainActor {
      if let player { player.repeatMode = mode }
      else { pending.repeatMode = mode }
    }
  }

  func getShuffleEnabled() throws -> Bool {
    onMainActor { player?.shuffleEnabled ?? pending.shuffleEnabled ?? false }
  }

  func setShuffleEnabled(enabled: Bool) throws {
    onMainActor {
      if let player { player.shuffleEnabled = enabled }
      else { pending.shuffleEnabled = enabled }
    }
  }

  func getPlaybackError() throws -> PlaybackError? {
    onMainActor { player?.nitroPlaybackError }
  }

  func retry() throws {
    commandPlayer("retry") { $0.retryNow() }
  }

  // MARK: - Sleep Timer

  func getSleepTimer() throws -> SleepTimer {
    onMainActor {
      player?.sleepTimerManager.get() ?? .first(NullType.null)
    }
  }

  func setSleepTimer(seconds: Double, fadeDuration: Double?) throws {
    commandPlayer("setSleepTimer") {
      $0.sleepTimerManager.set(seconds: seconds, fadeDuration: fadeDuration)
    }
  }

  func setSleepTimerToEndOfTrack() throws {
    commandPlayer("setSleepTimerToEndOfTrack") { $0.sleepTimerManager.setToEndOfTrack() }
  }

  func clearSleepTimer() throws -> Bool {
    onMainActor {
      player?.sleepTimerManager.clear() ?? false
    }
  }

  // MARK: - Queue Management

  func add(tracks: [Track], insertBeforeIndex: Double?) throws {
    try commandPlayer("add") { player in
      if let index = insertBeforeIndex {
        try player.add(tracks, at: Int(index))
      } else {
        try player.add(tracks)
      }
    }
  }

  func move(fromIndex: Double, toIndex: Double) throws {
    try commandPlayer("move") { try $0.move(fromIndex: Int(fromIndex), toIndex: Int(toIndex)) }
  }

  func remove(indexes: [Double]) throws {
    try commandPlayer("remove") { player in
      for index in indexes.sorted().reversed() {
        try player.remove(Int(index))
      }
    }
  }

  func removeUpcomingTracks() throws {
    commandPlayer("removeUpcomingTracks") { $0.removeUpcomingTracks() }
  }

  func skip(index: Double, initialPosition: Double?) throws {
    try commandPlayer("skip") { player in
      try player.skipTo(Int(index))
      if let position = initialPosition.flatMap({ finite($0, "skip") }) {
        player.seek(to: position)
      }
    }
  }

  func skipToNext(initialPosition: Double?) throws {
    commandPlayer("skipToNext") { player in
      player.next()
      if let position = initialPosition.flatMap({ finite($0, "skipToNext") }) {
        player.seek(to: position)
      }
    }
  }

  func skipToPrevious(initialPosition: Double?) throws {
    commandPlayer("skipToPrevious") { player in
      player.previous()
      if let position = initialPosition.flatMap({ finite($0, "skipToPrevious") }) {
        player.seek(to: position)
      }
    }
  }

  func setActiveTrackFavorited(favorited: Bool) throws {
    try commandPlayer("setActiveTrackFavorited") { _ in try favoriteActiveTrack(favorited) }
  }

  /// Sets the active track's favorite state, in the favorite set first so the now-playing heart
  /// flips at once. False when nothing is playing or the track has no identity.
  @discardableResult
  internal func favoriteActiveTrack(_ favorited: Bool) throws -> Bool {
    try onMainActor {
      guard let player, let track = player.currentTrack, let identity = track.identity else { return false }
      browserManager.setFavorited(identity: identity, favorited: favorited)
      // Hydration never overrides a track's own flag, so the queued copy must carry it.
      let updated = track.copying(favorited: favorited)
      try player.replace(player.queue.currentIndex, updated)
      // The only emit: onPlaybackActiveTrackChanged stays transition-only.
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
    try commandPlayer("toggleActiveTrackFavorited") { _ in
      try favoriteActiveTrack(!isActiveTrackFavorited())
    }
  }

  func setQueue(tracks: [Track], startIndex: Double?, startPosition: Double?) throws {
    try commandPlayer("setQueue") { player in
      try player.setQueue(
        tracks,
        initialIndex: startIndex.map { Int($0) } ?? 0,
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
      guard let tracks = player?.tracks else { return nil }
      let i = Int(index)
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
    onMainActor { player?.currentTrack.map(browserManager.hydrateFavorite) }
  }

  // MARK: - Now Playing

  func updateNowPlaying(update: NowPlayingUpdate?) throws {
    commandEngine("updateNowPlaying") { $0.nowPlayingUpdater.setOverride(update) }
  }

  func flashNowPlaying(update: NowPlayingUpdate, durationMs: Double) throws {
    commandEngine("flashNowPlaying") { $0.nowPlayingUpdater.flash(update, durationMs: durationMs) }
  }

  func clearNowPlayingFlash() throws {
    commandEngine("clearNowPlayingFlash") { $0.nowPlayingUpdater.clearFlash() }
  }

  func getNowPlaying() throws -> NowPlayingMetadata? {
    onMainActor { engine?.nowPlayingUpdater.nowPlaying }
  }

  func getTimedMetadata() throws -> TimedMetadata? {
    onMainActor { engine?.nowPlayingUpdater.timedMetadata }
  }

  // MARK: - Network

  func getOnline() throws -> Bool {
    // No `onMainActor` hop: `isOnline` is lock-protected, so this stays a cheap read on the JS thread.
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
    true
  }

  func openOutputPicker() throws {
    DispatchQueue.main.async { OutputPicker.present() }
  }
}
