import Testing

@testable import AudioBrowserTestable

// MARK: - Mocks

@MainActor
private final class BrowserMock: TrackSelectionBrowser {
  var trackLoadHandlerResult = false
  var expandQueueResult: (tracks: [Track], selectedIndex: Int)?
  var expandQueueError: Error?

  var trackLoadEvents: [TrackLoadEvent] = []
  var expandedPaths: [String] = []

  /// Expansions of these paths, and track-load handlers for these track ids, answer only after
  /// `release()`.
  var held: Set<String> = []
  private(set) var waiting: [CheckedContinuation<Void, Never>] = []

  func release() {
    let resuming = waiting
    waiting = []
    held = []
    for continuation in resuming {
      continuation.resume()
    }
  }

  private func hold(_ key: String?) async {
    guard let key, held.contains(key) else { return }
    await withCheckedContinuation { waiting.append($0) }
  }

  func awaitTrackLoadHandler(event: TrackLoadEvent) async -> Bool {
    trackLoadEvents.append(event)
    await hold(event.track.id)
    return trackLoadHandlerResult
  }

  func expandQueueFromContextualPath(_ path: String) async throws -> (tracks: [Track], selectedIndex: Int)? {
    expandedPaths.append(path)
    await hold(path)
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

@MainActor
private func yieldUntil(_ condition: () -> Bool, sourceLocation: SourceLocation = #_sourceLocation) async {
  for _ in 0 ..< 1000 where !condition() {
    await Task.yield()
  }
  #expect(condition(), sourceLocation: sourceLocation)
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

    // Queue tracks carry no id, so identity falls back to src.
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

  @Test func duplicateIdentityInQueue_pathMatchPinsTheTappedCopy() async {
    let (selector, browser, player) = makeSelector()
    let parentPath = "/playlist"
    func stamped(_ id: String, _ index: Int) -> String {
      BrowserPathHelper.build(parentPath: parentPath, trackId: id, index: index)
    }

    player.queueSourcePath = parentPath
    player.tracks = [
      Track(path: stamped("a", 0), src: "a"),
      Track(path: stamped("b", 1), src: "b"),
      Track(path: stamped("a", 2), src: "a"),
    ]

    let track = Track(path: stamped("a", 2), src: "a")
    let result = await selector.select(track: track, player: player)
    guard case let .play(.skipTo(index)) = result else {
      Issue.record("expected .play(.skipTo), got \(result)")
      return
    }
    #expect(index == 2)
    #expect(browser.expandedPaths.isEmpty)
  }

  @Test func indexStampedPathNotInQueue_reExpandsInsteadOfIdentitySkip() async {
    let (selector, browser, player) = makeSelector()
    let parentPath = "/city"
    func stamped(_ id: String, _ index: Int) -> String {
      BrowserPathHelper.build(parentPath: parentPath, trackId: id, index: index)
    }

    // "dup" is in the queue at another stamped index: an identity match would skip to it,
    // but a tap on another surface re-expands to scope the queue to that surface.
    player.queueSourcePath = parentPath
    player.tracks = [
      Track(path: stamped("dup", 0), src: "dup"),
      Track(path: stamped("x", 1), src: "x"),
    ]
    browser.expandQueueResult = (
      tracks: [Track(path: stamped("y", 4), src: "y"), Track(path: stamped("dup", 5), src: "dup")],
      selectedIndex: 1,
    )

    let track = Track(path: stamped("dup", 5), src: "dup")
    let result = await selector.select(track: track, player: player)
    guard case let .play(.setQueue(tracks, startIndex, sourcePath)) = result else {
      Issue.record("expected .play(.setQueue), got \(result)")
      return
    }
    #expect(tracks.count == 2)
    #expect(startIndex == 1)
    #expect(sourcePath == parentPath)
    #expect(browser.expandedPaths == [stamped("dup", 5)])
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

// MARK: - Disabled Track

@Suite("disabled track")
@MainActor
struct DisabledTrackTests {
  @Test func disabledPlayable_returnsNone() async {
    let (selector, browser, player) = makeSelector()
    let track = Track(id: "t1", src: "https://example.com/a.mp3", disabled: true)
    let result = await selector.select(track: track, player: player)
    guard case .none = result else {
      Issue.record("Expected .none for a disabled track, got \(result)")
      return
    }
    #expect(browser.trackLoadEvents.isEmpty)
  }

  @Test func disabledBrowsable_returnsNone() async {
    let (selector, _, player) = makeSelector()
    let track = Track(id: "t1", path: "/gone", disabled: true)
    let result = await selector.select(track: track, player: player)
    guard case .none = result else {
      Issue.record("Expected .none for a disabled browsable, got \(result)")
      return
    }
  }

  @Test func disabledContextual_neverExpands() async {
    let (selector, browser, player) = makeSelector()
    browser.expandQueueResult = (tracks: [Track(id: "a", src: "a")], selectedIndex: 0)
    let track = Track(
      id: "t1", path: "/home?__trackId=t1", src: "https://example.com/a.mp3", disabled: true,
    )
    let result = await selector.select(track: track, player: player)
    guard case .none = result else {
      Issue.record("Expected .none, got \(result)")
      return
    }
    #expect(browser.expandedPaths.isEmpty)
  }
}

// MARK: - Selection Order

@Suite("selection order")
@MainActor
struct SelectionOrderTests {
  /// A selection running beside the test, and its result once it has one.
  @MainActor
  private final class PendingSelection {
    private var result: TrackSelector.SelectionResult?

    init(_ selector: TrackSelector, _ track: Track, _ player: PlayerMock) {
      Task { result = await selector.select(track: track, player: player) }
    }

    func value() async -> TrackSelector.SelectionResult? {
      await yieldUntil { result != nil }
      return result
    }
  }

  /// Starts selecting a contextual track whose expansion stays held until `browser.release()`.
  private func selectHeldInExpansion(
    _ selector: TrackSelector, _ browser: BrowserMock, _ player: PlayerMock,
  ) async -> PendingSelection {
    let path = BrowserPathHelper.build(parentPath: "/library/radio", trackId: "older")
    browser.held = [path]
    browser.expandQueueResult = (tracks: [Track(id: "older", src: "older.mp3")], selectedIndex: 0)
    let selection = PendingSelection(selector, Track(id: "older", path: path, src: "older.mp3"), player)
    await yieldUntil { browser.waiting.count == 1 }
    return selection
  }

  @Test func olderExpansionFinishingLast_isSupersededAndNotOfferedToTheApp() async {
    let (selector, browser, player) = makeSelector()
    let older = await selectHeldInExpansion(selector, browser, player)

    let newer = await selector.select(track: Track(id: "newer", src: "newer.mp3"), player: player)
    guard case let .play(.loadTrack(loaded)) = newer else {
      Issue.record("expected .play(.loadTrack), got \(newer)")
      return
    }
    #expect(loaded.id == "newer")

    browser.release()
    let olderResult = await older.value()
    guard case .superseded = olderResult else {
      Issue.record("expected .superseded, got \(String(describing: olderResult))")
      return
    }
    #expect(browser.trackLoadEvents.map(\.track.id) == ["newer"])
  }

  @Test func olderTrackLoadHandlerFinishingLast_isSuperseded() async {
    let (selector, browser, player) = makeSelector()
    browser.held = ["older"]
    let older = PendingSelection(selector, Track(id: "older", src: "older.mp3"), player)
    await yieldUntil { browser.waiting.count == 1 }

    let newer = await selector.select(track: Track(id: "newer", src: "newer.mp3"), player: player)
    guard case .play = newer else {
      Issue.record("expected .play, got \(newer)")
      return
    }

    browser.release()
    let olderResult = await older.value()
    guard case .superseded = olderResult else {
      Issue.record("expected .superseded, got \(String(describing: olderResult))")
      return
    }
  }

  @Test func laterInterceptedSelection_supersedesTheOlderOne() async {
    let (selector, browser, player) = makeSelector()
    let older = await selectHeldInExpansion(selector, browser, player)

    browser.trackLoadHandlerResult = true
    let newer = await selector.select(track: Track(id: "newer", src: "newer.mp3"), player: player)
    guard case .intercepted = newer else {
      Issue.record("expected .intercepted, got \(newer)")
      return
    }

    browser.release()
    let olderResult = await older.value()
    guard case .superseded = olderResult else {
      Issue.record("expected .superseded, got \(String(describing: olderResult))")
      return
    }
    #expect(browser.trackLoadEvents.map(\.track.id) == ["newer"])
  }

  @Test func laterBrowse_leavesTheOlderSelectionToPlay() async {
    let (selector, browser, player) = makeSelector()
    let older = await selectHeldInExpansion(selector, browser, player)

    let browse = await selector.select(track: Track(id: "folder", path: "/library"), player: player)
    guard case .browse = browse else {
      Issue.record("expected .browse, got \(browse)")
      return
    }

    browser.release()
    let olderResult = await older.value()
    guard case .play(.setQueue) = olderResult else {
      Issue.record("expected .play(.setQueue), got \(String(describing: olderResult))")
      return
    }
  }

  @Test func laterDisabledTrack_leavesTheOlderSelectionToPlay() async {
    let (selector, browser, player) = makeSelector()
    let older = await selectHeldInExpansion(selector, browser, player)

    _ = await selector.select(track: Track(id: "gone", src: "gone.mp3", disabled: true), player: player)

    browser.release()
    let olderResult = await older.value()
    guard case .play(.setQueue) = olderResult else {
      Issue.record("expected .play(.setQueue), got \(String(describing: olderResult))")
      return
    }
  }
}
