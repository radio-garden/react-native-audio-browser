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
      await MainActor.run { [weak self] in
        guard let self else { return }
        // Set the category now, but activate only when playback produces output.
        self.audioSession.configure(options.ios)

        // A re-setup REPLACES the player, so the outgoing one has to be torn down
        // and un-published first. No-op on first setup.
        destroyPlayer()

        let newEngine = AVPlayerEngine(
          callbacks: self,
          resolver: browserManager.requests,
          audioSession: audioSession,
          networkStatus: networkMonitor,
          bufferDuration: options.ios?.buffer ?? 0,
          nowPlayingEnabled: options.autoUpdateNowPlayingMetadata ?? true,
          nowPlayingFormatter: options.nowPlayingMetadataFormatter,
        )
        let newPlayer = Player(engine: newEngine, callbacks: self, retry: RetryLoop(networkMonitor: networkMonitor))
        if let retry = options.retry { newPlayer.retry.updatePolicy(from: retry) }
        newEngine.nowPlayingUpdater.source = newPlayer
        engine = newEngine
        player = newPlayer
        audioSession.player = newPlayer

        // `options.keepSessionAliveOnError` is ignored: on iOS an error never clears the now-playing
        // info, and next/previous follow the queue, not the state.

        // The bundled runtime options and initial state are part of the atomic launch
        // description. Stage the state first so setup options and earlier pre-setup setter
        // calls resolve by strict last-write-wins, then apply everything to the live player.
        if let bundled = options.options { updateOptions(options: bundled) }
        if let mode = options.repeatMode { pendingRepeatMode = mode }
        if let intent = options.playWhenReady { pendingPlayWhenReady = intent }
        applyPendingPlayerState()
        // The stored options outlive the player, so a re-setup applies them to the new one.
        applyOptions()
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
    engine?.remoteCommandController.enable(
      commands: playerOptions.capabilities.remoteCommands,
      forwardJumpInterval: playerOptions.forwardJumpInterval,
      backwardJumpInterval: playerOptions.backwardJumpInterval,
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
