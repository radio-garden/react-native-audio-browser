/// Player state set before the player exists, by setup options or the setters; last write wins.
/// `apply(to:)` consumes it, so a later re-setup does not replay it.
@MainActor
struct PendingPlayerState {
  var playWhenReady: Bool?
  var repeatMode: RepeatMode?
  var shuffleEnabled: Bool?

  mutating func apply(to player: Player) {
    if let repeatMode { player.repeatMode = repeatMode }
    if let shuffleEnabled { player.shuffleEnabled = shuffleEnabled }
    if let playWhenReady { player.setPlayWhenReady(playWhenReady) }
    self = PendingPlayerState()
  }
}
