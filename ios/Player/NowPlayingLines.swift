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

/// What the now-playing surface renders from: the player's track and state.
@MainActor
protocol NowPlayingSource: AnyObject {
  var currentTrack: Track? { get }
  var currentTime: Double { get }
  var playWhenReady: Bool { get }
  var isStalled: Bool { get }
  var nitroPlaybackError: PlaybackError? { get }
}
