/// The repeat mode and play intent set while no player takes them: before the first setup, and
/// from a setup's call until the last queued rebuild, which applies them. Last write wins. It is the
/// one place listeners hear these two values from, each change once.
@MainActor
final class StagedPlayerState {
  private let currentPlayer: () -> Player?
  private let onRepeatModeChanged: (RepeatMode) -> Void
  private let onPlayWhenReadyChanged: (Bool) -> Void
  private var setupsInFlight = 0
  private var stagedRepeatMode: RepeatMode?
  private var stagedPlayWhenReady: Bool?
  private var heardRepeatMode = RepeatMode.off
  private var heardPlayWhenReady = false

  init(
    currentPlayer: @escaping () -> Player?,
    onRepeatModeChanged: @escaping (RepeatMode) -> Void,
    onPlayWhenReadyChanged: @escaping (Bool) -> Void,
  ) {
    self.currentPlayer = currentPlayer
    self.onRepeatModeChanged = onRepeatModeChanged
    self.onPlayWhenReadyChanged = onPlayWhenReadyChanged
  }

  /// Opens a setup's window, then stages its options, so a later setter wins over them.
  func setupStarted(repeatMode: RepeatMode?, playWhenReady: Bool?) {
    setupsInFlight += 1
    if let repeatMode { setRepeatMode(repeatMode) }
    if let playWhenReady { setPlayWhenReady(playWhenReady) }
  }

  /// Closes a setup's window; every setup calls it once. `player` is the one it built, which takes
  /// what is staged, or `nil` when a JS reload dropped this instance before it built one.
  func setupFinished(player: Player?) {
    setupsInFlight -= 1
    if setupsInFlight == 0 {
      if let player, let stagedRepeatMode { player.repeatMode = stagedRepeatMode }
      if let player, let stagedPlayWhenReady { player.setPlayWhenReady(stagedPlayWhenReady) }
      stagedRepeatMode = nil
      stagedPlayWhenReady = nil
    }
    announce()
  }

  /// Tells listeners what the getters answer, where it differs from what they last heard.
  func announce() {
    let mode = repeatMode()
    if mode != heardRepeatMode {
      heardRepeatMode = mode
      onRepeatModeChanged(mode)
    }
    let intent = playWhenReady()
    if intent != heardPlayWhenReady {
      heardPlayWhenReady = intent
      onPlayWhenReadyChanged(intent)
    }
  }

  private var isStaging: Bool {
    currentPlayer() == nil || setupsInFlight > 0
  }

  func setRepeatMode(_ mode: RepeatMode) {
    if isStaging { stagedRepeatMode = mode } else { currentPlayer()?.repeatMode = mode }
    announce()
  }

  func repeatMode() -> RepeatMode {
    stagedRepeatMode ?? currentPlayer()?.repeatMode ?? .off
  }

  func setPlayWhenReady(_ intent: Bool) {
    if isStaging { stagedPlayWhenReady = intent } else { currentPlayer()?.setPlayWhenReady(intent) }
    announce()
  }

  func togglePlayback() {
    setPlayWhenReady(!isPlaying())
  }

  func stop() {
    stop { $0.stop() }
  }

  /// `stop()` as the remote's stop button does it: a live stream pauses instead.
  func stopFromRemote() {
    stop { $0.stopFromRemote() }
  }

  private func stop(_ stop: (Player) -> Void) {
    if isStaging { stagedPlayWhenReady = false }
    currentPlayer().map(stop)
    announce()
  }

  func playWhenReady() -> Bool {
    stagedPlayWhenReady ?? currentPlayer()?.playWhenReady ?? false
  }

  /// Whether a toggle pauses: the staged intent while one is staged, so a toggle can take a staged
  /// play back, else `Player.remoteIsPlaying`.
  func isPlaying() -> Bool {
    stagedPlayWhenReady ?? (currentPlayer()?.remoteIsPlaying == true)
  }
}
