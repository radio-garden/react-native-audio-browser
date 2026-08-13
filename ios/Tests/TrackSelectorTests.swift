import Testing

@testable import AudioBrowserTestable

// MARK: - Mocks

@MainActor
private final class BrowserMock: TrackSelectionBrowser {
  var trackLoadHandlerResult = false
  var expandQueueResult: (tracks: [Track], selectedIndex: Int)?
  var expandQueueError: Error?

  // Record calls for verification
  var trackLoadEvents: [TrackLoadEvent] = []
  var expandedPaths: [String] = []

  func awaitTrackLoadHandler(event: TrackLoadEvent) async -> Bool {
    trackLoadEvents.append(event)
    return trackLoadHandlerResult
  }

  func expandQueueFromContextualPath(_ path: String) async throws -> (tracks: [Track], selectedIndex: Int)? {
    expandedPaths.append(path)
    if let error = expandQueueError {
      throw error
    }
    return expandQueueResult
  }
}

@MainActor
private final class PlayerMock: TrackSelectionPlayer {
  var tracks: [Track] = []
  var queueSourcePath: String?
}

private enum MockError: Error {
  case expansion
}

// MARK: - Helpers

@MainActor
private func makeSelector() -> (TrackSelector, BrowserMock, PlayerMock) {
  let browser = BrowserMock()
  let player = PlayerMock()
  let selector = TrackSelector(browserManager: browser)
  return (selector, browser, player)
}

// MARK: - Browsable Track

@Suite("browsable track")
@MainActor
struct BrowsableTrackTests {
  @Test func pathOnly_returnsBrowse() async {
    let (selector, _, player) = makeSelector()
    let track = Track(id: "t1", path: "/some/path")
    let result = await selector.select(track: track, player: player)
    guard case let .browse(path) = result else {
      Issue.record("expected .browse, got \(result)")
      return
    }
    #expect(path == "/some/path")
  }
}

// MARK: - No src or path

@Suite("no src or path")
@MainActor
struct NoSrcOrPathTests {
  @Test func returnsNone() async {
    let (selector, _, player) = makeSelector()
    let track = Track(id: "t1")
    let result = await selector.select(track: track, player: player)
    guard case .none = result else {
      Issue.record("expected .none, got \(result)")
      return
    }
  }
}

// MARK: - Playable Track

@Suite("playable track")
@MainActor
struct PlayableTrackTests {
  @Test func hasSrc_returnsLoadTrack() async {
    let (selector, browser, player) = makeSelector()
    let track = Track(id: "t1", src: "https://example.com/audio.mp3")
    let result = await selector.select(track: track, player: player)
    guard case let .play(intent) = result else {
      Issue.record("expected .play, got \(result)")
      return
    }
    guard case let .loadTrack(loaded) = intent else {
      Issue.record("expected .loadTrack, got \(intent)")
      return
    }
    #expect(loaded.src == "https://example.com/audio.mp3")
    #expect(browser.trackLoadEvents.count == 1)
  }

  @Test func handlerIntercepts_returnsIntercepted() async {
    let (selector, browser, player) = makeSelector()
    browser.trackLoadHandlerResult = true
    let track = Track(id: "t1", src: "https://example.com/audio.mp3")
    let result = await selector.select(track: track, player: player)
    guard case .intercepted = result else {
      Issue.record("expected .intercepted, got \(result)")
      return
    }
    #expect(browser.trackLoadEvents.count == 1)
  }
}

// MARK: - Contextual path Queue Reuse

@Suite("contextual path queue reuse")
@MainActor
struct ContextualPathQueueReuseTests {
  @Test func matchingQueueSourcePath_returnsSkipTo() async {
    let (selector, browser, player) = makeSelector()
    let parentPath = "/library/radio"
    let trackSrc = "song.mp3"
    let contextualPath = BrowserPathHelper.build(parentPath: parentPath, trackId: trackSrc)

    // Id-less queue tracks: identity falls back to src.
    player.queueSourcePath = parentPath
    player.tracks = [
      Track(src: "other.mp3"),
      Track(src: "song.mp3"),
      Track(src: "another.mp3"),
    ]

    let track = Track(path: contextualPath, src: trackSrc)
    let result = await selector.select(track: track, player: player)
    guard case let .play(intent) = result else {
      Issue.record("expected .play, got \(result)")
      return
    }
    guard case let .skipTo(index) = intent else {
      Issue.record("expected .skipTo, got \(intent)")
      return
    }
    #expect(index == 1)
    #expect(browser.trackLoadEvents.count == 1)
    #expect(browser.expandedPaths.isEmpty)
  }

  // Skip-in-place matches by id when queue tracks carry ids: the contextual
  // trackId is the row's identity (its id), and the queue row is found even
  // though its src differs textually from the tapped track's.
  @Test func matchingQueueSourcePath_skipsToTrackByIdWhenSrcDiffers() async {
    let (selector, browser, player) = makeSelector()
    let parentPath = "/library/radio"
    let contextualPath = BrowserPathHelper.build(parentPath: parentPath, trackId: "b")

    player.queueSourcePath = parentPath
    player.tracks = [
      Track(id: "a", src: "other.mp3"),
      Track(id: "b", src: "https://cdn.example/song.mp3?token=queue"),
      Track(id: "c", src: "another.mp3"),
    ]

    let track = Track(id: "b", path: contextualPath, src: "song.mp3")
    let result = await selector.select(track: track, player: player)
    guard case let .play(intent) = result else {
      Issue.record("expected .play, got \(result)")
      return
    }
    guard case let .skipTo(index) = intent else {
      Issue.record("expected .skipTo, got \(intent)")
      return
    }
    #expect(index == 1)
    #expect(browser.expandedPaths.isEmpty)
  }

  @Test func handlerIntercepts_returnsIntercepted() async {
    let (selector, browser, player) = makeSelector()
    browser.trackLoadHandlerResult = true
    let parentPath = "/library/radio"
    let trackSrc = "song.mp3"
    let contextualPath = BrowserPathHelper.build(parentPath: parentPath, trackId: trackSrc)

    player.queueSourcePath = parentPath
    player.tracks = [
      Track(src: "song.mp3"),
    ]

    let track = Track(path: contextualPath, src: trackSrc)
    let result = await selector.select(track: track, player: player)
    guard case .intercepted = result else {
      Issue.record("expected .intercepted, got \(result)")
      return
    }
    #expect(browser.expandedPaths.isEmpty)
  }
}

// MARK: - Contextual path Expansion

@Suite("contextual path expansion")
@MainActor
struct ContextualPathExpansionTests {
  @Test func expands_returnsSetQueue() async {
    let (selector, browser, player) = makeSelector()
    let parentPath = "/library/radio"
    let trackSrc = "song.mp3"
    let contextualPath = BrowserPathHelper.build(parentPath: parentPath, trackId: trackSrc)

    let expandedTracks = [
      Track(id: "a", src: "first.mp3"),
      Track(id: "b", src: "song.mp3"),
      Track(id: "c", src: "last.mp3"),
    ]
    browser.expandQueueResult = (tracks: expandedTracks, selectedIndex: 1)

    let track = Track(id: "t1", path: contextualPath, src: trackSrc)
    let result = await selector.select(track: track, player: player)
    guard case let .play(intent) = result else {
      Issue.record("expected .play, got \(result)")
      return
    }
    guard case let .setQueue(tracks, startIndex, sourcePath) = intent else {
      Issue.record("expected .setQueue, got \(intent)")
      return
    }
    #expect(tracks.count == 3)
    #expect(startIndex == 1)
    #expect(sourcePath == parentPath)
    #expect(browser.expandedPaths == [contextualPath])
    #expect(browser.trackLoadEvents.count == 1)
  }

  @Test func handlerIntercepts_returnsIntercepted() async {
    let (selector, browser, player) = makeSelector()
    browser.trackLoadHandlerResult = true
    let parentPath = "/library/radio"
    let trackSrc = "song.mp3"
    let contextualPath = BrowserPathHelper.build(parentPath: parentPath, trackId: trackSrc)

    browser.expandQueueResult = (
      tracks: [Track(id: "a", src: "song.mp3")],
      selectedIndex: 0,
    )

    let track = Track(id: "t1", path: contextualPath, src: trackSrc)
    let result = await selector.select(track: track, player: player)
    guard case .intercepted = result else {
      Issue.record("expected .intercepted, got \(result)")
      return
    }
  }

  @Test func expansionReturnsNil_fallsBackToLoadTrack() async {
    let (selector, browser, player) = makeSelector()
    let parentPath = "/library/radio"
    let trackSrc = "song.mp3"
    let contextualPath = BrowserPathHelper.build(parentPath: parentPath, trackId: trackSrc)

    browser.expandQueueResult = nil

    let track = Track(id: "t1", path: contextualPath, src: trackSrc)
    let result = await selector.select(track: track, player: player)
    guard case let .play(intent) = result else {
      Issue.record("expected .play, got \(result)")
      return
    }
    guard case let .loadTrack(loaded) = intent else {
      Issue.record("expected .loadTrack, got \(intent)")
      return
    }
    #expect(loaded.src == trackSrc)
  }

  @Test func expansionThrows_fallsBackToLoadTrack() async {
    let (selector, browser, player) = makeSelector()
    let parentPath = "/library/radio"
    let trackSrc = "song.mp3"
    let contextualPath = BrowserPathHelper.build(parentPath: parentPath, trackId: trackSrc)

    browser.expandQueueError = MockError.expansion

    let track = Track(id: "t1", path: contextualPath, src: trackSrc)
    let result = await selector.select(track: track, player: player)
    guard case let .play(intent) = result else {
      Issue.record("expected .play, got \(result)")
      return
    }
    guard case let .loadTrack(loaded) = intent else {
      Issue.record("expected .loadTrack, got \(intent)")
      return
    }
    #expect(loaded.src == trackSrc)
  }
}
