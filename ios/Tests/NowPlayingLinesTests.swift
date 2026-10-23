@testable import AudioBrowserTestable
import Testing

@Suite("NowPlayingLines.resolve")
struct NowPlayingLinesTests {
  private let track = Track(title: "Station", artist: "City", album: "Country")

  @Test func withoutLayersTheTrackShows() {
    #expect(NowPlayingLines.resolve(track: track) == NowPlayingLines(title: "Station", artist: "City", album: "Country"))
  }

  @Test func theOverrideOutranksTheTrack() {
    let lines = NowPlayingLines.resolve(track: track, override: NowPlayingUpdate(title: "Show", artist: nil, album: nil))
    #expect(lines == NowPlayingLines(title: "Show", artist: "City", album: "Country"))
  }

  @Test func theFormatterOutranksTheOverride() {
    let lines = NowPlayingLines.resolve(
      track: track,
      formatted: NowPlayingUpdate(title: "Song", artist: nil, album: nil),
      override: NowPlayingUpdate(title: "Show", artist: "Host", album: nil),
    )
    #expect(lines == NowPlayingLines(title: "Song", artist: "Host", album: "Country"))
  }

  @Test func theFlashOutranksEverything() {
    let lines = NowPlayingLines.resolve(
      track: track,
      flash: NowPlayingUpdate(title: nil, artist: "Can't skip", album: nil),
      formatted: NowPlayingUpdate(title: "Song", artist: "Artist", album: nil),
      override: NowPlayingUpdate(title: "Show", artist: "Host", album: "Album"),
    )
    #expect(lines == NowPlayingLines(title: "Song", artist: "Can't skip", album: "Album"))
  }

  @Test func eachLineFallsBackSeparately() {
    let lines = NowPlayingLines.resolve(
      track: Track(title: "Station"),
      flash: NowPlayingUpdate(title: nil, artist: nil, album: nil),
    )
    #expect(lines == NowPlayingLines(title: "Station", artist: nil, album: nil))
  }
}
