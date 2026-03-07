import Testing

@testable import AudioBrowserTestable

// MARK: - Mocks

@MainActor
private final class BrowserMock: TrackSelectionBrowser {
  var trackLoadHandlerResult = false
  var expandQueueResult: (tracks: [Track], selectedIndex: Int)? = nil
  var expandQueueError: Error? = nil

  // Record calls for verification
  var trackLoadEvents: [TrackLoadEvent] = []
  var expandedUrls: [String] = []

  func awaitTrackLoadHandler(event: TrackLoadEvent) async -> Bool {
    trackLoadEvents.append(event)
    return trackLoadHandlerResult
  }

  func expandQueueFromContextualUrl(_ url: String) async throws -> (tracks: [Track], selectedIndex: Int)? {
    expandedUrls.append(url)
    if let error = expandQueueError {
      throw error
    }
    return expandQueueResult
  }
}

@MainActor
private final class PlayerMock: TrackSelectionPlayer {
  var tracks: [Track] = []
  var queueSourcePath: String? = nil
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
  @Test func urlOnly_returnsBrowse() async {
    let (selector, _, player) = makeSelector()
    let track = Track(id: "t1", url: "/some/path")
    let result = await selector.select(track: track, player: player)
    guard case .browse(let url) = result else {
      Issue.record("expected .browse, got \(result)")
      return
    }
    #expect(url == "/some/path")
  }
}

// MARK: - No src or url

@Suite("no src or url")
@MainActor
struct NoSrcOrUrlTests {
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
    guard case .play(let intent) = result else {
      Issue.record("expected .play, got \(result)")
      return
    }
    guard case .loadTrack(let loaded) = intent else {
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

// MARK: - Contextual URL Queue Reuse

@Suite("contextual URL queue reuse")
@MainActor
struct ContextualUrlQueueReuseTests {
  @Test func matchingQueueSourcePath_returnsSkipTo() async {
    let (selector, browser, player) = makeSelector()
    let parentPath = "/library/radio"
    let trackSrc = "song.mp3"
    let contextualUrl = BrowserPathHelper.build(parentPath: parentPath, trackId: trackSrc)

    player.queueSourcePath = parentPath
    player.tracks = [
      Track(id: "a", src: "other.mp3"),
      Track(id: "b", src: "song.mp3"),
      Track(id: "c", src: "another.mp3"),
    ]

    let track = Track(id: "t1", url: contextualUrl, src: trackSrc)
    let result = await selector.select(track: track, player: player)
    guard case .play(let intent) = result else {
      Issue.record("expected .play, got \(result)")
      return
    }
    guard case .skipTo(let index) = intent else {
      Issue.record("expected .skipTo, got \(intent)")
      return
    }
    #expect(index == 1)
    #expect(browser.trackLoadEvents.count == 1)
    #expect(browser.expandedUrls.isEmpty)
  }

  @Test func handlerIntercepts_returnsIntercepted() async {
    let (selector, browser, player) = makeSelector()
    browser.trackLoadHandlerResult = true
    let parentPath = "/library/radio"
    let trackSrc = "song.mp3"
    let contextualUrl = BrowserPathHelper.build(parentPath: parentPath, trackId: trackSrc)

    player.queueSourcePath = parentPath
    player.tracks = [
      Track(id: "a", src: "song.mp3"),
    ]

    let track = Track(id: "t1", url: contextualUrl, src: trackSrc)
    let result = await selector.select(track: track, player: player)
    guard case .intercepted = result else {
      Issue.record("expected .intercepted, got \(result)")
      return
    }
  }
}

// MARK: - Contextual URL Expansion

@Suite("contextual URL expansion")
@MainActor
struct ContextualUrlExpansionTests {
  @Test func expands_returnsSetQueue() async {
    let (selector, browser, player) = makeSelector()
    let parentPath = "/library/radio"
    let trackSrc = "song.mp3"
    let contextualUrl = BrowserPathHelper.build(parentPath: parentPath, trackId: trackSrc)

    let expandedTracks = [
      Track(id: "a", src: "first.mp3"),
      Track(id: "b", src: "song.mp3"),
      Track(id: "c", src: "last.mp3"),
    ]
    browser.expandQueueResult = (tracks: expandedTracks, selectedIndex: 1)

    let track = Track(id: "t1", url: contextualUrl, src: trackSrc)
    let result = await selector.select(track: track, player: player)
    guard case .play(let intent) = result else {
      Issue.record("expected .play, got \(result)")
      return
    }
    guard case .setQueue(let tracks, let startIndex, let sourcePath) = intent else {
      Issue.record("expected .setQueue, got \(intent)")
      return
    }
    #expect(tracks.count == 3)
    #expect(startIndex == 1)
    #expect(sourcePath == parentPath)
    #expect(browser.expandedUrls == [contextualUrl])
    #expect(browser.trackLoadEvents.count == 1)
  }

  @Test func handlerIntercepts_returnsIntercepted() async {
    let (selector, browser, player) = makeSelector()
    browser.trackLoadHandlerResult = true
    let parentPath = "/library/radio"
    let trackSrc = "song.mp3"
    let contextualUrl = BrowserPathHelper.build(parentPath: parentPath, trackId: trackSrc)

    browser.expandQueueResult = (
      tracks: [Track(id: "a", src: "song.mp3")],
      selectedIndex: 0
    )

    let track = Track(id: "t1", url: contextualUrl, src: trackSrc)
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
    let contextualUrl = BrowserPathHelper.build(parentPath: parentPath, trackId: trackSrc)

    browser.expandQueueResult = nil

    let track = Track(id: "t1", url: contextualUrl, src: trackSrc)
    let result = await selector.select(track: track, player: player)
    guard case .play(let intent) = result else {
      Issue.record("expected .play, got \(result)")
      return
    }
    guard case .loadTrack(let loaded) = intent else {
      Issue.record("expected .loadTrack, got \(intent)")
      return
    }
    #expect(loaded.src == trackSrc)
  }

  @Test func expansionThrows_fallsBackToLoadTrack() async {
    let (selector, browser, player) = makeSelector()
    let parentPath = "/library/radio"
    let trackSrc = "song.mp3"
    let contextualUrl = BrowserPathHelper.build(parentPath: parentPath, trackId: trackSrc)

    browser.expandQueueError = MockError.expansion

    let track = Track(id: "t1", url: contextualUrl, src: trackSrc)
    let result = await selector.select(track: track, player: player)
    guard case .play(let intent) = result else {
      Issue.record("expected .play, got \(result)")
      return
    }
    guard case .loadTrack(let loaded) = intent else {
      Issue.record("expected .loadTrack, got \(intent)")
      return
    }
    #expect(loaded.src == trackSrc)
  }
}
