import AVFoundation
import AVKit
import Foundation
import MediaPlayer
import NitroModules
import os.log

public extension HybridAudioBrowser {
  // MARK: - Player Setup

  func setupPlayer(options: NativeSetupPlayerOptions) throws -> Promise<Void> {
    // Claimed here, on the calling thread: the last call sets the category last and builds the
    // player last.
    let setup = setupTurns.enqueue { [weak self] in await self?.runSetup(options) }
    return Promise.async { await setup.value }
  }

  @MainActor
  private func runSetup(_ options: NativeSetupPlayerOptions) async {
    // A JS reload's new instance took over while this waited its turn.
    guard !didDropJSCallbacks else { return }
    // Sets the category only; the session activates when playback produces output. Awaited
    // before the player is built, so it never plays under another category.
    await AudioSessionController.shared.configure(options.ios)
    // A JS reload's new instance took over during the wait.
    guard !didDropJSCallbacks else { return }

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

    // Stage the state before applying it, so setup options and pre-setup setter calls
    // resolve by strict last-write-wins.
    if let bundled = options.options { updateOptions(options: bundled) }
    if let mode = options.repeatMode { pendingRepeatMode = mode }
    if let intent = options.playWhenReady { pendingPlayWhenReady = intent }
    applyPendingPlayerState()
    // The new player starts at its defaults without emitting them; listeners still hold the old values.
    if let oldPlayer {
      if oldPlayer.repeatMode != newPlayer.repeatMode {
        playerDidChangeRepeatMode(RepeatModeChangedEvent(repeatMode: newPlayer.repeatMode))
      }
      if oldPlayer.shuffleEnabled != newPlayer.shuffleEnabled {
        playerDidChangeShuffleEnabled(newPlayer.shuffleEnabled)
      }
      if oldPlayer.playWhenReady != newPlayer.playWhenReady {
        playerDidChangePlayWhenReady(newPlayer.playWhenReady)
      }
    }
    // The stored options outlive the player, so a re-setup applies them to the new one.
    applyOptions()
    playerAndConfiguredBrowser.check()
  }

  /// Applies state staged before the player existed, by setup options or pre-setup setters.
  /// Consumed on apply so a later re-setup doesn't replay it.
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
