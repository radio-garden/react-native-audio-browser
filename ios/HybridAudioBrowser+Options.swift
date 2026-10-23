import AVFoundation
import AVKit
import Foundation
import MediaPlayer
import NitroModules
import os.log

public extension HybridAudioBrowser {
  // MARK: - Player Setup

  func setupPlayer(options: NativeSetupPlayerOptions) throws -> Promise<Void> {
    Promise.async {
      // Create player and configure on main actor
      await MainActor.run { [weak self] in
        guard let self else { return }
        // Set the category now, but activate only when playback produces output.
        self.audioSession.configure(options.ios)

        // A re-setup REPLACES the player, so the outgoing one has to be torn down
        // and un-published first. No-op on first setup.
        destroyPlayer()

        let newEngine = AVPlayerEngine(callbacks: self, resolver: browserManager.requests, audioSession: audioSession)
        if let buffer = options.ios?.buffer { newEngine.bufferDuration = buffer }
        newEngine.nowPlayingUpdater.enabled = options.autoUpdateNowPlayingMetadata ?? true
        newEngine.nowPlayingUpdater.formatter = options.nowPlayingMetadataFormatter
        newEngine.nowPlayingUpdater.isOnline = networkMonitor.isOnline
        let newPlayer = Player(engine: newEngine)
        newPlayer.callbacks = self
        newPlayer.retry.networkMonitor = networkMonitor
        if let retry = options.retry { newPlayer.retry.updatePolicy(from: retry) }
        newEngine.nowPlayingUpdater.source = newPlayer
        engine = newEngine
        player = newPlayer
        audioSession.player = newPlayer

        // Connectivity fans out here, whether or not JS subscribes: the JS event, the
        // now-playing stall line, and reconnecting a stalled stream. The monitor mutates on main.
        networkMonitor.onChanged = { [weak self] isOnline in
          MainActor.assumeIsolated {
            self?.onOnlineChanged(isOnline)
            self?.engine?.nowPlayingUpdater.isOnline = isOnline
            if isOnline { self?.player?.networkDidReturn() }
          }
        }

        // `options.keepSessionAliveOnError` is deliberately ignored here: iOS needs no masking.
        // A terminal error keeps the now-playing info (never cleared on error), and next/previous
        // stay enabled (gated on queue position, not state). So the
        // session stays controllable through errors by default — the behavior Android achieves via
        // InterceptingPlayer is already the iOS norm.

        // The bundled runtime options and initial state are part of the atomic launch
        // description. Stage the state first so setup options and earlier pre-setup setter
        // calls resolve by strict last-write-wins, then apply everything to the live player.
        if let bundled = options.options { updateOptions(options: bundled) }
        if let mode = options.repeatMode { pendingRepeatMode = mode }
        if let intent = options.playWhenReady { pendingPlayWhenReady = intent }
        applyPendingPlayerState()
        // The stored options outlive the player, so a re-setup applies them to the new one.
        applyOptions()

        // Notify listeners that player is ready (e.g., CarPlay)
        playerAndConfiguredBrowser.check()
      }
    }
  }

  /// Applies initial player state staged before the player existed — from setup options or the
  /// imperative setters called pre-setup. Consumed on apply so a later re-setup doesn't replay
  /// stale state.
  @MainActor
  private func applyPendingPlayerState() {
    guard let player else { return }
    if let mode = pendingRepeatMode {
      player.repeatMode = mode
      pendingRepeatMode = nil
    }
    if let intent = pendingPlayWhenReady {
      player.setPlayWhenReady(intent)
      pendingPlayWhenReady = nil
    }
  }

  func updateOptions(options: NativeUpdateOptions) {
    onMainActor {
      playerOptions.update(from: options)
      applyOptions()
      onOptionsChanged(playerOptions.toOptions())
      playerOptionsChangedEmitter.emit(())
    }
  }

  /// Applies the stored options to the browser, the player and its engine. Idempotent.
  @MainActor
  private func applyOptions() {
    // The `favorite` capability is the one favoriting switch: it also drives the row hearts.
    browserManager.setFavoriteEnabled(playerOptions.capabilities.favoriteEnabled)
    engine?.remoteCommands = playerOptions.capabilities.buildRemoteCommands(
      forwardJumpInterval: NSNumber(value: playerOptions.forwardJumpInterval),
      backwardJumpInterval: NSNumber(value: playerOptions.backwardJumpInterval),
      playbackRates: playerOptions.playbackRates,
    )
    player?.setProgressUpdateInterval(playerOptions.progressUpdateEventInterval)
    player?.setPlaybackIntervalEnabled(playerOptions.playbackIntervalEnabled)
  }

  func getOptions() throws -> Options {
    onMainActor { playerOptions.toOptions() }
  }

  func setPlaybackIntervalEnabled(enabled: Bool) {
    onMainActor {
      playerOptions.playbackIntervalEnabled = enabled
      player?.setPlaybackIntervalEnabled(enabled)
    }
  }
}
