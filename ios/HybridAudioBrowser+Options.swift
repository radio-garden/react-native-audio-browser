import AVFoundation
import AVKit
import Foundation
import MediaPlayer
import NitroModules
import os.log

public extension HybridAudioBrowser {
  // MARK: - Player Setup

  func setupPlayer(options: NativeSetupPlayerOptions) throws -> Promise<Void> {
    // Opened on the calling thread, not in the turn, so a setter called after this one stages for
    // its player.
    onMainActor {
      stagedPlayerState.setupStarted(repeatMode: options.repeatMode, playWhenReady: options.playWhenReady)
    }
    // Claimed here, on the calling thread: the last call sets the category last and builds the
    // player last.
    let setup = setupTurns.enqueue { [weak self] in
      guard let self else { return }
      await stagedPlayerState.setupFinished(player: runSetup(options))
    }
    return Promise.async { await setup.value }
  }

  @MainActor
  private func runSetup(_ options: NativeSetupPlayerOptions) async -> Player? {
    // A JS reload's new instance took over while this waited its turn.
    guard !didDropJSCallbacks else { return nil }
    // Sets the category only; the session activates when playback produces output. Awaited
    // before the player is built, so it never plays under another category.
    await AudioSessionController.shared.configure(options.ios)
    // A JS reload's new instance took over during the wait.
    guard !didDropJSCallbacks else { return nil }

    // A re-setup replaces the player, so the outgoing one is torn down first. No-op on first setup.
    let oldPlayer = player
    oldPlayer?.sleepTimerManager.clear()
    destroyPlayer()

    let newEngine = AVPlayerEngine(
      callbacks: self,
      resolver: browserManager.requests,
      networkStatus: networkMonitor,
      bufferDuration: options.ios?.buffer ?? 0,
      nowPlayingEnabled: options.autoUpdateNowPlayingMetadata ?? true,
      nowPlayingFormatter: options.nowPlayingMetadataFormatter,
    )
    let newPlayer = Player(
      engine: newEngine,
      audioSession: AudioSessionController.shared,
      callbacks: self,
      retry: RetryLoop(online: networkMonitor.isOnline),
    )
    if let retry = options.retry { newPlayer.retry.updatePolicy(from: retry) }
    newEngine.nowPlayingUpdater.source = newPlayer
    engine = newEngine
    player = newPlayer
    AudioSessionController.shared.player = newPlayer
    browserManager.favorites.queue = newPlayer

    // `options.keepSessionAliveOnError` is ignored: on iOS an error never clears the now-playing
    // info, and next/previous follow the queue, not the state.

    if let bundled = options.options { updateOptions(options: bundled) }
    // The new player starts at its defaults without emitting them; listeners still hold the old
    // value. The repeat mode and play intent are announced when the window closes.
    if let oldPlayer, oldPlayer.shuffleEnabled != newPlayer.shuffleEnabled {
      playerDidChangeShuffleEnabled(newPlayer.shuffleEnabled)
    }
    // The stored options outlive the player, so a re-setup applies them to the new one.
    applyOptions()
    playerAndConfiguredBrowser.check()
    return newPlayer
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
