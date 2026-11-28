import Foundation

/// What a restore's expansion of a persisted track's contextual path gives.
enum RestoreExpansion {
  /// A fresh queue, and the page it came from.
  case queue(tracks: [Track], selectedIndex: Int, sourcePath: String)
  /// The page now holds the track only disabled: it never plays.
  case refused
  /// No fresh queue (no page, a failed page): the stored queue loads instead.
  case unavailable
}

/// Loads the persisted session as the queue at setup, paused (`SetupPlayerOptions.restore`).
enum PlaybackRestore {
  /// The intent a setup applies: paused when it restores `session`, whatever it asked for, so a
  /// play arriving after setup is the one the restored queue keeps.
  static func setupIntent(requested: Bool?, session: PersistedPlaybackState?) -> Bool? {
    session == nil ? requested : false
  }

  /// Restores `session` into `player`, keeping the play intent: a play or pause sent since setup is
  /// the one the queue keeps. `repeatMode` is the setup's, which wins over the stored one. A queue
  /// set since `version` (the player's `queueVersion` at setup), a player no longer `isCurrent`, a
  /// session `cleared` meanwhile, or a track its page refuses, is left alone.
  @MainActor
  static func run(
    _ player: Player,
    session: PersistedPlaybackState,
    since version: Int,
    repeatMode: RepeatMode?,
    expand: @MainActor (_ path: String, _ track: Track, _ stored: [Track]) async -> RestoreExpansion,
    isCurrent: @MainActor () -> Bool,
    cleared: @MainActor () -> Bool,
  ) async {
    guard player.currentTrack == nil, player.queueVersion == version, !cleared() else { return }
    let track = session.track.toNitro()
    player.previewNowPlaying(track)
    var expanded: (tracks: [Track], selectedIndex: Int, sourcePath: String)?
    if let path = track.path {
      switch await expand(path, track, session.storedQueue?.tracks ?? []) {
      case let .queue(tracks, selectedIndex, sourcePath): expanded = (tracks, selectedIndex, sourcePath)
      case .refused:
        if isCurrent(), player.queueVersion == version { player.endNowPlayingPreview() }
        return
      case .unavailable: break
      }
    }
    guard isCurrent(), player.queueVersion == version else { return }
    guard !cleared() else {
      player.endNowPlayingPreview()
      return
    }
    player.restore(session, queue: expanded, playWhenReady: nil, repeatMode: repeatMode)
  }
}
