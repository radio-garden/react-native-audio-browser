#if canImport(NitroModules)
  import NitroModules
#endif

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

struct PublishedNowPlayingLines {
  let trackId: String?
  let lines: NowPlayingLines

  init(_ lines: NowPlayingLines, for track: Track) {
    trackId = track.identity ?? track.path
    self.lines = lines
  }

  /// `lines` when they were published for `track`, else nil.
  func lines(for track: Track) -> NowPlayingLines? {
    trackId == (track.identity ?? track.path) ? lines : nil
  }
}

@MainActor
protocol NowPlayingSource: AnyObject {
  var currentTrack: Track? { get }
  var currentTime: Double { get }
  var playWhenReady: Bool { get }
  var isStalled: Bool { get }
  var nitroPlaybackError: PlaybackError? { get }
}
