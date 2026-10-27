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

@Suite("PublishedNowPlayingLines")
struct PublishedNowPlayingLinesTests {
  private let station = Track(src: "https://s/a.mp3", title: "Station", artist: "City")
  private let liveSong = NowPlayingLines(title: "Station", artist: "Song - Artist", album: nil)

  @Test func theStampedTrackGetsItsLines() {
    let published = PublishedNowPlayingLines(liveSong, for: station)
    #expect(published.lines(for: station) == liveSong)
  }

  @Test func anotherTrackGetsNone() {
    let published = PublishedNowPlayingLines(liveSong, for: station)
    #expect(published.lines(for: Track(src: "https://s/b.mp3", title: "Station", artist: "City")) == nil)
  }

  @Test func aTrackWithoutIdentityMatchesOnItsPath() {
    let folder = Track(path: "/a", title: "Folder")
    let published = PublishedNowPlayingLines(liveSong, for: folder)
    #expect(published.lines(for: folder) == liveSong)
    #expect(published.lines(for: Track(path: "/b", title: "Folder")) == nil)
  }
}
