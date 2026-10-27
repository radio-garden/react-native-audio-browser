#if canImport(NitroModules)
  import NitroModules
#endif

/// The title, artist and album lines the now-playing surface shows.
struct NowPlayingLines: Equatable {
  let title: String
  let artist: String?
  let album: String?

  /// Each line from the first of `flash`, `formatted` and `override` that sets it, else the
  /// track's own.
  static func resolve(
    track: Track,
    flash: NowPlayingUpdate? = nil,
    formatted: NowPlayingUpdate? = nil,
    override: NowPlayingUpdate? = nil,
  ) -> NowPlayingLines {
    let layers = [flash, formatted, override].compactMap(\.self)
    return NowPlayingLines(
      title: layers.lazy.compactMap(\.title).first ?? track.title,
      artist: layers.lazy.compactMap(\.artist).first ?? track.artist,
      album: layers.lazy.compactMap(\.album).first ?? track.album,
    )
  }
}

/// The lines last stamped on the now-playing surface, and the track they were stamped for.
struct PublishedNowPlayingLines {
  let trackId: String?
  let lines: NowPlayingLines

  init(_ lines: NowPlayingLines, for track: Track) {
    trackId = track.identity ?? track.path
    self.lines = lines
  }

  /// `lines` when they were stamped for `track`, else nil.
  func lines(for track: Track) -> NowPlayingLines? {
    trackId == (track.identity ?? track.path) ? lines : nil
  }
}

/// What the now-playing surface renders from: the player's track and state.
@MainActor
protocol NowPlayingSource: AnyObject {
  var currentTrack: Track? { get }
  var currentTime: Double { get }
  var playWhenReady: Bool { get }
  var isStalled: Bool { get }
  var nitroPlaybackError: PlaybackError? { get }
}
