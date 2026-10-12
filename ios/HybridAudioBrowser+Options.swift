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
        // and un-published first. `TrackPlayer` has no deinit: dropping the
        // reference alone would leave its AVPlayer and MPNowPlayingSession alive.
        // No-op on first setup.
        player?.destroy()

        let newPlayer = TrackPlayer(callbacks: self, resolver: browserManager.requests, networkMonitor: networkMonitor)
        if let buffer = options.ios?.buffer { newPlayer.bufferDuration = buffer }
        if let retry = options.retry { newPlayer.retryConfig = retry }
        newPlayer.nowPlayingUpdater.enabled = options.autoUpdateNowPlayingMetadata ?? true
        newPlayer.nowPlayingUpdater.formatter = options.nowPlayingMetadataFormatter
        newPlayer.nowPlayingUpdater.isOnline = networkMonitor.isOnline
        player = newPlayer
        audioSession.player = newPlayer

        // Connectivity fans out here, whether or not JS subscribes: the JS event, the
        // now-playing stall line, and reconnecting a stalled stream. The monitor mutates on main.
        networkMonitor.onChanged = { [weak self] isOnline in
          MainActor.assumeIsolated {
            self?.onOnlineChanged(isOnline)
            self?.player?.networkDidChange(isOnline: isOnline)
          }
        }

        // `options.keepSessionAliveOnError` is deliberately ignored here: iOS needs no masking.
        // A terminal error resolves the playback state to *paused*
        // (PlaybackCoordinator.applySideEffects), the now-playing info is retained (never cleared
        // on error), and next/previous stay enabled (gated on queue position, not state). So the
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
      player.playWhenReady = intent
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
    guard let player else { return }

    let remoteCommands = playerOptions.capabilities.buildRemoteCommands(
      forwardJumpInterval: NSNumber(value: playerOptions.forwardJumpInterval),
      backwardJumpInterval: NSNumber(value: playerOptions.backwardJumpInterval),
      playbackRates: playerOptions.playbackRates,
    )

    player.remoteCommands = remoteCommands
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
