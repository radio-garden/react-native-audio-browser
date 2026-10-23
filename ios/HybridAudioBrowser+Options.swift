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

        let newEngine = AVPlayerEngine(callbacks: self, resolver: browserManager.requests)
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
        if let bundled = options.options {
          updateOptions(options: bundled)
        } else {
          // updateOptions applies remote commands itself; without bundled options, apply the
          // defaults (play, pause, next, previous, seekTo) here.
          applyRemoteCommands()
        }
        if let mode = options.repeatMode { pendingRepeatMode = mode }
        if let intent = options.playWhenReady { pendingPlayWhenReady = intent }
        applyPendingPlayerState()

        // Push the resolved event cadence unconditionally. `updateOptions` applies
        // the interval only when it *differs* from the stored one, but the store
        // outlives the player — so on a re-setup with unchanged options the new
        // player would come up with no progress timer at all.
        player?.setProgressUpdateInterval(playerOptions.progressUpdateEventInterval)
        player?.setPlaybackIntervalEnabled(playbackIntervalEnabled)

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
      let previousInterval = playerOptions.progressUpdateEventInterval

      // Update stored options
      playerOptions.update(from: options)

      // Propagate the favorite flag to the browser so it can hydrate
      // row hearts (the `favorite` capability is the single favoriting switch).
      browserManager.setFavoriteEnabled(playerOptions.capabilities.favoriteEnabled)

      // Apply remote commands to player
      applyRemoteCommands()

      // Apply progress update interval if changed
      if playerOptions.progressUpdateEventInterval != previousInterval {
        player?.setProgressUpdateInterval(playerOptions.progressUpdateEventInterval)
      }

      onOptionsChanged(playerOptions.toOptions())
      playerOptionsChangedEmitter.emit(())
    }
  }

  /// Converts capabilities to remote commands and applies them to the player
  @MainActor
  private func applyRemoteCommands() {
    guard let engine else { return }

    let remoteCommands = playerOptions.capabilities.buildRemoteCommands(
      forwardJumpInterval: NSNumber(value: playerOptions.forwardJumpInterval),
      backwardJumpInterval: NSNumber(value: playerOptions.backwardJumpInterval),
      playbackRates: playerOptions.playbackRates,
    )

    engine.remoteCommands = remoteCommands
  }

  func getOptions() throws -> Options {
    onMainActor { playerOptions.toOptions() }
  }

  func setPlaybackIntervalEnabled(enabled: Bool) {
    onMainActor {
      playbackIntervalEnabled = enabled
      player?.setPlaybackIntervalEnabled(enabled)
    }
  }
}
