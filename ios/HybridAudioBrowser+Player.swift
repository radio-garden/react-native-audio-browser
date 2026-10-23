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
      player.load(track)
    }
  }

  func reset() throws {
    onMainActor { player?.clear() }
  }

  func play() throws {
    onMainActor { player?.play() }
  }

  func pause() throws {
    onMainActor { player?.pause() }
  }

  func togglePlayback() throws {
    onMainActor { player?.togglePlayback() }
  }

  func stop() throws {
    onMainActor { player?.stop() }
  }

  func setPlayWhenReady(playWhenReady: Bool) throws {
    onMainActor {
      // Pre-setup this stages the intent for the player to come up with — never a no-op.
      if let player { player.setPlayWhenReady(playWhenReady) }
      else { pendingPlayWhenReady = playWhenReady }
    }
  }

  func getPlayWhenReady() throws -> Bool {
    onMainActor { player?.playWhenReady ?? pendingPlayWhenReady ?? false }
  }

  func seekTo(position: Double) throws {
    onMainActor { player?.seek(to: position) }
  }

  func seekBy(offset: Double) throws {
    onMainActor { player?.seek(by: offset) }
  }

  func seekToLiveEdge() throws {
    onMainActor { player?.seekToLiveEdge() }
  }

  func setVolume(level: Double) throws {
    onMainActor { engine?.volume = Float(level) }
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
    onMainActor { player?.repeatMode ?? pendingRepeatMode ?? .off }
  }

  func setRepeatMode(mode: RepeatMode) throws {
    onMainActor {
      // Pre-setup this stages the mode for the player to come up with — never a no-op.
      if let player { player.repeatMode = mode }
      else { pendingRepeatMode = mode }
    }
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
    // Re-resolve rather than replay the cached URL: short-lived URLs/tokens
    // may have expired since the failure (matches the internal retry path).
    onMainActor { player?.reloadResolving(startFromCurrentTime: true) }
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
        try player.add(tracks, at: Int(index))
      } else {
        player.add(tracks)
      }
    }
  }

  func move(fromIndex: Double, toIndex: Double) throws {
    try onMainActor {
      try player?.move(fromIndex: Int(fromIndex), toIndex: Int(toIndex))
    }
  }

  func remove(indexes: [Double]) throws {
    try onMainActor {
      guard let player else { return }
      // Remove in reverse order to maintain index validity
      for index in indexes.sorted().reversed() {
        try player.remove(Int(index))
      }
    }
  }

  func removeUpcomingTracks() throws {
    onMainActor { player?.removeUpcomingTracks() }
  }

  func skip(index: Double, initialPosition: Double?) throws {
    try onMainActor {
      try player?.skipTo(Int(index))
      if let position = initialPosition {
        player?.seek(to: position)
      }
    }
  }

  func skipToNext(initialPosition: Double?) throws {
    onMainActor {
      player?.next()
      if let position = initialPosition {
        player?.seek(to: position)
      }
    }
  }

  func skipToPrevious(initialPosition: Double?) throws {
    onMainActor {
      player?.previous()
      if let position = initialPosition {
        player?.seek(to: position)
      }
    }
  }

  func setActiveTrackFavorited(favorited: Bool) throws {
    try favoriteActiveTrack(favorited)
  }

  /// Sets the active track's favorite state: in the favorite set first, so the now-playing
  /// heart (hydrated from it) flips at once, then on the queued track. False when nothing is
  /// playing or the track has no identity.
  @discardableResult
  internal func favoriteActiveTrack(_ favorited: Bool) throws -> Bool {
    try onMainActor {
      guard let player, let track = player.currentTrack, let identity = track.identity else { return false }
      browserManager.setFavorited(identity: identity, favorited: favorited)
      // Back in the queue carrying the flag, since hydration never overrides a track's own value.
      let updated = track.copying(favorited: favorited)
      try player.replace(player.queue.currentIndex, updated)
      // The only emit: a favorite toggle mutates the active track in place, so
      // onPlaybackActiveTrackChanged stays transition-only.
      let event = FavoriteChangedEvent(track: updated, favorited: favorited)
      onFavoriteChanged(event)
      favoriteChangedEmitter.emit(event)
      return true
    }
  }

  /// Whether the active (now-playing) track is favorited, per the authoritative
  /// favorite set (via `getActiveTrack`'s hydration).
  internal func isActiveTrackFavorited() -> Bool {
    (try? getActiveTrack())?.favorited ?? false
  }

  func toggleActiveTrackFavorited() throws {
    try favoriteActiveTrack(!isActiveTrackFavorited())
  }

  func setQueue(tracks: [Track], startIndex: Double?, startPosition: Double?) throws {
    onMainActor {
      guard let player else { return }
      player.setQueue(
        tracks,
        initialIndex: startIndex.map { Int($0) } ?? 0,
        startPositionMs: startPosition.map { $0 * 1000 },
      )
    }
  }

  func getQueue() throws -> [Track] {
    onMainActor { player?.tracks ?? [] }
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
    // Hydrate `favorited` from the authoritative favorite set — the now-playing
    // track is loaded outside the browse cache, so its own flag isn't otherwise
    // kept in sync as favorites change. This is the single source of truth for
    // "is the active track favorited" (now-playing heart, toggle direction, JS).
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

  // MARK: - Network

  func getOnline() throws -> Bool {
    // No `onMainActor` hop: `isOnline` is lock-protected, so this stays a
    // cheap read on the JS thread instead of blocking on the main queue.
    networkMonitor.isOnline
  }

  // MARK: - System Volume

  func getSystemVolume() throws -> Double {
    AudioSessionController.volume
  }

  func setSystemVolume(volume _: Double) throws {
    // iOS doesn't provide a public API to set system volume programmatically.
    // Users must adjust volume via hardware buttons or Control Center.
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
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      guard let windowScene = UIApplication.shared.activeWindowScene,
            let window = windowScene.windows.first(where: { $0.isKeyWindow }),
            var topController = window.rootViewController
      else {
        self.logger.error("openOutputPicker: no active window scene / root view controller")
        return
      }

      // Walk up to the topmost presented view controller
      while let presented = topController.presentedViewController {
        topController = presented
      }

      // AVRoutePickerView has no public API to present its picker
      // programmatically, so we add it to the hierarchy and tap its internal
      // button. Two requirements that are easy to get wrong:
      //   1. The view must be *visible* to the system (a `isHidden` source view
      //      suppresses the presentation), so we keep it transparent instead.
      //   2. The internal button can be nested below the top-level subviews on
      //      modern iOS, so we search recursively rather than only direct children.
      let routePicker = AVRoutePickerView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
      routePicker.alpha = 0.01
      topController.view.addSubview(routePicker)

      let button = Self.firstButton(in: routePicker)
      self.logger.notice(
        "openOutputPicker: hierarchy=\(Self.describeHierarchy(routePicker), privacy: .public), buttonFound=\(button != nil, privacy: .public)",
      )

      if let button {
        button.sendActions(for: .touchUpInside)
      } else {
        self.logger.error("openOutputPicker: no UIButton found inside AVRoutePickerView")
      }

      // Keep the source view in the hierarchy long enough for the picker to
      // present (on iPad it anchors a popover to this view).
      DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
        routePicker.removeFromSuperview()
      }
    }
  }

  /// Depth-first search for the first `UIButton` inside a view tree.
  private static func firstButton(in view: UIView) -> UIButton? {
    if let button = view as? UIButton { return button }
    for subview in view.subviews {
      if let button = firstButton(in: subview) { return button }
    }
    return nil
  }

  /// Compact description of a view tree, e.g. `AVRoutePickerView[UIButton[UIImageView]]`.
  private static func describeHierarchy(_ view: UIView) -> String {
    let name = String(describing: type(of: view))
    let children = view.subviews.map(describeHierarchy).joined(separator: ", ")
    return children.isEmpty ? name : "\(name)[\(children)]"
  }
}
